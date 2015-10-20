(ns onyx.state.filter.rocksdb
  (:import [org.rocksdb RocksDB RocksIterator ColumnFamilyDescriptor ColumnFamilyHandle ColumnFamilyOptions 
            Snapshot Options ReadOptions BloomFilter BlockBasedTableConfig CompressionType]
           [org.apache.commons.io FileUtils])
  (:require [onyx.state.state-extensions :as state-extensions]
            [clojure.core.async :refer [chan >!! <!! alts!! timeout go <! alts! close! thread]]
            [onyx.state.rocksdb :as r]
            [onyx.static.default-vals :refer [arg-or-default defaults]]
            [onyx.compression.nippy :as nippy]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]))

(defrecord RocksDbInstance [dir db id-counter buckets bucket rotation-thread shutdown-ch])

(defn build-bucket [^RocksDB db]
  (.createColumnFamily db 
                       (ColumnFamilyDescriptor. (.getBytes (str (java.util.UUID/randomUUID))) 
                                                (ColumnFamilyOptions.))))

(defn clear-bucket! [db bucket]
  (.dropColumnFamily ^RocksDB db ^ColumnFamilyHandle bucket))

(def max-buckets 255)

(defn rotate-bucket! 
  "Rotates to the next bucket, and then starts the dropped one"
  [db buckets bucket]
  (let [new-bucket (build-bucket db)
        new-buckets (swap! buckets conj new-bucket)]
    (reset! bucket new-bucket)
    (when (> (count new-buckets) max-buckets)
      (swap! buckets (fn [bks] (vec (drop 1 bks))))
      (let [dropped-bucket (first new-buckets)]
        (clear-bucket! db dropped-bucket)))))

(defn start-rotation-thread! [shutdown-ch peer-opts db id-counter buckets bucket]
  (thread
    (let [rotation-sleep (arg-or-default :onyx.rocksdb.filter/rotation-check-interval-ms peer-opts)
          elements-per-bucket (arg-or-default :onyx.rocksdb.filter/rotate-filter-bucket-every-n peer-opts)] 
      (loop []
        (let [timeout-ch (timeout rotation-sleep)
              ch (second (alts!! [timeout-ch shutdown-ch]))]
          (when (= ch timeout-ch)
            (try
              (when (> @id-counter elements-per-bucket)
                (info "Rotating filter bucket after" elements-per-bucket "elements.")
                (rotate-bucket! db buckets bucket)
                (reset! id-counter 0))
              (catch Throwable e
                (fatal e)))
            (recur)))))))

(defmethod state-extensions/initialize-filter :rocksdb [_ {:keys [onyx.core/peer-opts onyx.core/id onyx.core/task-id] :as event}] 
  (let [_ (RocksDB/loadLibrary)
        compression-opt (arg-or-default :onyx.rocksdb.filter/compression peer-opts)
        block-size (arg-or-default :onyx.rocksdb.filter/block-size peer-opts)
        block-cache-size (arg-or-default :onyx.rocksdb.filter/peer-block-cache-size peer-opts)
        base-dir-path (arg-or-default :onyx.rocksdb.filter/base-dir peer-opts)
        bloom-filter-bits (arg-or-default :onyx.rocksdb.filter/bloom-filter-bits peer-opts)
        base-dir-path-file ^java.io.File (java.io.File. ^String base-dir-path)
        _ (when-not (.exists base-dir-path-file) (.mkdir base-dir-path-file))
        db-dir (str base-dir-path "/" id "_" task-id)
        bloom-filter (BloomFilter. bloom-filter-bits false)
        block-config (doto (BlockBasedTableConfig.)
                       (.setBlockSize block-size)
                       (.setBlockCacheSize block-size)
                       (.setFilter bloom-filter))
        options (doto (Options.)
                  (.setCompressionType ^CompressionType (r/compression-option->type compression-opt))
                  (.setCreateIfMissing true)
                  (.setTableFormatConfig block-config))
        db (RocksDB/open options db-dir)
        initial-bucket (build-bucket db)
        buckets (atom [initial-bucket])
        bucket (atom initial-bucket)
        id-counter (atom 0)
        shutdown-ch (chan 1)
        rotation-thread (start-rotation-thread! shutdown-ch peer-opts db id-counter buckets bucket)]
    (->RocksDbInstance db-dir db id-counter buckets bucket rotation-thread shutdown-ch)))

(defmethod state-extensions/apply-filter-id onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _ id] 
  (let [k ^bytes (nippy/localdb-compress id)
        v (byte-array 0)]
    (swap! (:id-counter rocks-db) inc)
    (.put ^RocksDB (:db rocks-db) ^ColumnFamilyHandle @(:bucket rocks-db) k ^bytes v))
  ;; Expects a filter back
  rocks-db)

(defmethod state-extensions/filter? onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _ id] 
  (let [k ^bytes (nippy/localdb-compress id)
        strbuf (StringBuffer.)
        db ^RocksDB (:db rocks-db)]
    (some (fn [^ColumnFamilyHandle bucket]
            (.keyMayExist db bucket k strbuf))
          @(:buckets rocks-db))))

(defmethod state-extensions/close-filter onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _]
  (close! (:shutdown-ch rocks-db))
  ;; Block until background processing has been stopped before closing the db
  (<!! (:rotation-thread rocks-db))
  (.close ^RocksDB (:db rocks-db))
  (FileUtils/deleteDirectory (java.io.File. ^String (:dir rocks-db))))

(defmethod state-extensions/restore-filter onyx.state.filter.rocksdb.RocksDbInstance 
  [{:keys [db bucket id-counter] :as rocks-db} event snapshot]
  (reset! id-counter (:id-counter snapshot))
  (reset! bucket (:bucket snapshot))
  (run! (fn [[k v]]
          (.put ^RocksDB db ^bytes k ^bytes v)) 
        (:kvs snapshot))
  rocks-db)

(defmethod state-extensions/snapshot-filter onyx.state.filter.rocksdb.RocksDbInstance 
  [filter-state _] 
  (let [db ^RocksDB (:db filter-state)
        snapshot ^Snapshot (.getSnapshot db)
        bucket @(:bucket filter-state)
        id-counter @(:id-counter filter-state)
        read-options ^ReadOptions (doto (ReadOptions.)
                                    (.setSnapshot snapshot))]
    (future 
      (let [iterator ^RocksIterator (.newIterator db read-options)]
        (try
          (.seekToFirst iterator)
          {:bucket bucket 
           :id-counter id-counter
           :kvs (loop [ids (list)]
                  (if (.isValid iterator)
                    (let [id (list (.key iterator) (.value iterator))] 
                      (.next iterator)
                      (recur (conj ids id)))
                    ids))}
          (finally
            (.releaseSnapshot db (.snapshot read-options))
            (.dispose iterator)))))))
