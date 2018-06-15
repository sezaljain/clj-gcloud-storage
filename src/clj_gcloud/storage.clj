(ns clj-gcloud.storage
  (:require [clj-gcloud
             [coerce :refer :all]
             [common :refer [build-service array-type]]]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io])
  (:import (com.google.cloud.storage BucketInfo Blob BlobId BlobInfo BucketInfo$Builder Storage StorageOptions
                                     Storage$BucketGetOption Bucket Storage$BucketTargetOption
                                     Storage$BucketSourceOption BlobInfo$Builder Storage$CopyRequest
                                     Storage$BlobTargetOption BlobWriteChannel Storage$BlobWriteOption
                                     Blob$BlobSourceOption BlobReadChannel Storage$BlobListOption CopyWriter)
           (com.google.api.gax.paging Page)
           (java.nio.channels Channels ReadableByteChannel WritableByteChannel)
           (java.io InputStream OutputStream FileInputStream File)
           (com.google.common.io ByteStreams)))

(create-clj-coerce BucketInfo [:name :location :storage-class])
(create-clj-coerce Blob [:blob-id :name :content-type])
(create-clj-coerce BlobId [:bucket :name])
(create-clj-coerce BlobInfo [:blob-id :cache-control :create-time :update-time :delete-time
                             :content-disposition :content-encoding :content-language :content-type])

(defn bucket-info
  ^BucketInfo
  [bucket-name options]
  (let [opt-map {:index-page         (fn [^BucketInfo$Builder b v] (.setIndexPage b v))
                 :location           (fn [^BucketInfo$Builder b v] (.setLocation b v))
                 :not-found-page     (fn [^BucketInfo$Builder b v] (.setNotFoundPage b v))
                 :storage-class      (fn [^BucketInfo$Builder b v] (.setStorageClass b v))
                 :versioning-enabled (fn [^BucketInfo$Builder b v] (.setVersioningEnabled b (boolean v)))}
        builder (.toBuilder (BucketInfo/of bucket-name))]
    (doseq [[k v] options
            :let [set-fn (get opt-map k)]
            :when set-fn]
      (set-fn builder v))
    (.build builder)))

(defn init
  ^Storage
  [options]
  (let [builder (StorageOptions/newBuilder)]
    (build-service builder options)))

;; BUCKETS
(defn get-bucket
  ^BucketInfo
  [^Storage storage ^String bucket-name]
  (let [^#=(array-type Storage$BucketGetOption) no-options (into-array Storage$BucketGetOption [])]
    (.get storage bucket-name no-options)))

(defn create-bucket
  ^Bucket
  [^Storage storage ^BucketInfo bucket-info]
  (let [^#=(array-type Storage$BucketTargetOption) no-options (into-array Storage$BucketTargetOption [])]
    (.create storage bucket-info no-options)))

(defn get-or-create-bucket
  "Fetches or creates a bucket if it doesn't exist"
  ^Bucket
  [^Storage storage ^BucketInfo info]
  (let [bucket-name (.getName info)
        bucket      (get-bucket storage bucket-name)]
    (or bucket
        (do
          (log/info "Creating new bucket:" bucket-name)
          (create-bucket storage info)))))

(defn delete-bucket
  ^Boolean
  [^Storage storage ^String bucket-name]
  (let [^#=(array-type Storage$BucketSourceOption) no-opts (into-array Storage$BucketSourceOption [])]
    (.delete storage bucket-name no-opts)))

;; BLOBS
(declare ->blob-id)

(defn read-gs-uri
  ^BlobId
  [gs-uri]
  (let [[scheme host path] (clojure.string/split gs-uri #"[/]+" 3)]
    (if (= "gs:" scheme)
      (->blob-id host path)
      (throw (ex-info "Invalid scheme" {:input gs-uri})))))

(defn ->blob-id
  ^BlobId
  ([bucket name]
   (BlobId/of bucket name))
  ([gs-uri-or-blob-id]
   (if (instance? BlobId gs-uri-or-blob-id)
     gs-uri-or-blob-id
     (read-gs-uri gs-uri-or-blob-id))))

(defn blob-info
  ^BlobInfo
  [blob-id options]
  (let [opt-map {:cache-control       (fn [^BlobInfo$Builder b v] (.setCacheControl b v))
                 :content-disposition (fn [^BlobInfo$Builder b v] (.setContentDisposition b v))
                 :content-encoding    (fn [^BlobInfo$Builder b v] (.setContentEncoding b v))
                 :content-language    (fn [^BlobInfo$Builder b v] (.setContentLanguage b v))
                 :content-type        (fn [^BlobInfo$Builder b v] (.setContentType b v))}
        builder (BlobInfo/newBuilder blob-id)]
    (doseq [[k v] options
            :let [set-fn (get opt-map k)]
            :when set-fn]
      (set-fn builder v))
    (.build builder)))

(defn copy
  ^BlobInfo
  [^Storage storage ^BlobId source-blob-id ^BlobId target-blob-id]
  (-> (.copy storage (Storage$CopyRequest/of source-blob-id target-blob-id))
      (.getResult)))

(defn create-blob
  ^Blob
  [^Storage storage ^BlobInfo blob-info]
  (let [^#=(array-type Storage$BlobTargetOption) no-options (into-array Storage$BlobTargetOption [])]
    (.create storage blob-info no-options)))

(defn create-blob-writer
  ^BlobWriteChannel
  [^Storage storage ^BlobInfo blob-info]
  (let [^#=(array-type Storage$BlobWriteOption) no-opts (into-array Storage$BlobWriteOption [])]
    (.writer storage blob-info no-opts)))

(defn get-blob
  ^Blob
  ([^Storage storage ^BlobId blob-id]
   (.get storage blob-id)))

(defn delete-blob
  ^java.lang.Boolean
  ([^Storage storage ^BlobId blob-id]
   (.delete storage blob-id)))

(defn copy-blob
  ^Blob
  ([^Blob source-blob ^BlobId target-blob-id]
   (let [^#=(array-type Blob$BlobSourceOption) no-opts (into-array Blob$BlobSourceOption [])
         ^CopyWriter writer                            (.copyTo source-blob target-blob-id no-opts)]
     (.getResult writer))))

(defn ->blob-list-options
  [options]
  (let [opt-map {:current-directory (fn [_] (Storage$BlobListOption/currentDirectory))
                 :page-size         (fn [v] (Storage$BlobListOption/pageSize (long v)))
                 :page-token        (fn [v] (Storage$BlobListOption/pageToken v))
                 :prefix            (fn [v] (Storage$BlobListOption/prefix v))
                 :versions          (fn [v] (Storage$BlobListOption/versions (boolean v)))}]
    (->> (for [[k v] options
               :let [opt-fn (get opt-map k)]
               :when opt-fn]
           (opt-fn v))
         (into-array Storage$BlobListOption))))

(defn list-blobs
  ^Page
  [^Storage storage ^String bucket-name options]
  (.list storage bucket-name (->blob-list-options options)))

(defn- split-bucket-path
  [gs-uri]
  (let [{:keys [bucket name]} (->clj (read-gs-uri gs-uri))]
    [bucket name]))

(defn ls
  "Usage:
     (ls storage gs-uri [options])
     (ls storage bucket path [options])"
  ([^Storage storage gs-uri]
   (let [[bucket path] (split-bucket-path gs-uri)]
     (ls storage bucket path)))
  ([^Storage storage bucket path]
   (if (map? path)
     (let [options path
           [bucket path] (split-bucket-path bucket)]
       (ls storage bucket path options))
     (ls storage bucket path {})))
  ([^Storage storage bucket path options]
   (page->seq (list-blobs storage bucket (merge options
                                                (if (clojure.string/blank? path)
                                                  {} {:prefix path}))))))

;;
;; Utility IO functions
;;

(defn read-channel
  ^BlobReadChannel
  [^Blob blob]
  (.reader blob (into-array Blob$BlobSourceOption [])))

(defn write-channel
  ^BlobWriteChannel
  [^Blob blob]
  (.writer blob (into-array Storage$BlobWriteOption [])))

(defn ->input-stream
  ^InputStream
  [^ReadableByteChannel channel]
  (Channels/newInputStream channel))

(defn ->output-stream
  ^OutputStream
  [^WritableByteChannel channel]
  (Channels/newOutputStream channel))

(defn stream->file
  "Writes an input stream to disk"
  [^InputStream input-stream local-path]
  (io/copy input-stream (io/file local-path)))

(defn file->stream
  "Writes an local file to stream"
  [local-path ^OutputStream output-stream]
  (io/copy (io/file local-path) output-stream))

;;
;; Convenience functions
;;

(defn copy-file-to-storage
  "Convenience function for copying a local file to a blob to storage.
  By default the type is JSON encoded in UTF-8"
  ([storage src dest-uri]
   (copy-file-to-storage storage src dest-uri
                         :content-type "application/json"
                         :content-encoding "UTF-8"))
  ([^Storage storage ^File src dest-gs-uri & options]
   (let [opts (apply array-map options)
         info (-> dest-gs-uri ->blob-id (blob-info opts))]
     (with-open [from (.getChannel (FileInputStream. src))
                 to   (create-blob-writer storage info)]
       (ByteStreams/copy from to)))))