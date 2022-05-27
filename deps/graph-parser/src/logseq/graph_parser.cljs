(ns logseq.graph-parser
  "Main ns used by logseq app to parse graph from source files"
  (:require [datascript.core :as d]
            [logseq.graph-parser.extract :as extract]
            [logseq.graph-parser.util :as gp-util]
            [logseq.graph-parser.date-time-util :as date-time-util]
            [logseq.graph-parser.config :as gp-config]
            [clojure.string :as string]
            [clojure.set :as set]))

(defn- db-set-file-content!
  "Modified copy of frontend.db.model/db-set-file-content!"
  [conn path content]
  (let [tx-data {:file/path path
                 :file/content content}]
    (d/transact! conn [tx-data] {:skip-refresh? true})))

(defn parse-file
  "Parse file and save parsed data to the given db. Main parse fn used by logseq app"
  [conn file content {:keys [new? delete-blocks-fn new-graph? extract-options]
                    :or {new? true
                         new-graph? false
                         delete-blocks-fn (constantly [])}}]
  (db-set-file-content! conn file content)
  (let [format (gp-util/get-format file)
        file-content [{:file/path file}]
        tx (if (contains? gp-config/mldoc-support-formats format)
             (let [extract-options' (merge {:block-pattern (gp-config/get-block-pattern format)
                                            :date-formatter "MMM do, yyyy"
                                            :supported-formats (gp-config/supported-formats)}
                                           extract-options
                                           {:db @conn})
                   [pages blocks]
                   (extract/extract-blocks-pages file content extract-options')
                   delete-blocks (delete-blocks-fn (first pages) file)
                   block-ids (map (fn [block] {:block/uuid (:block/uuid block)}) blocks)
                   block-refs-ids (->> (mapcat :block/refs blocks)
                                       (filter (fn [ref] (and (vector? ref)
                                                              (= :block/uuid (first ref)))))
                                       (map (fn [ref] {:block/uuid (second ref)}))
                                       (seq))
                   ;; To prevent "unique constraint" on datascript
                   block-ids (set/union (set block-ids) (set block-refs-ids))
                   pages (extract/with-ref-pages pages blocks)
                   pages-index (map #(select-keys % [:block/name]) pages)]
               ;; does order matter?
               (concat file-content pages-index delete-blocks pages block-ids blocks))
             file-content)
        tx (concat tx [(cond-> {:file/path file}
                               new?
                               ;; TODO: use file system timestamp?
                               (assoc :file/created-at (date-time-util/time-ms)))])]
    (d/transact! conn (gp-util/remove-nils tx) (when new-graph? {:new-graph? true}))))

(defn filter-files
  "Filters files in preparation for parsing. Only includes files that are
  supported by parser"
  [files]
  (let [support-files (filter
                       (fn [file]
                         (let [format (gp-util/get-format (:file/path file))]
                           (contains? (set/union #{:edn :css} gp-config/mldoc-support-formats) format)))
                       files)
        support-files (sort-by :file/path support-files)
        {journals true non-journals false} (group-by (fn [file] (string/includes? (:file/path file) "journals/")) support-files)
        {built-in true others false} (group-by (fn [file]
                                                 (or (string/includes? (:file/path file) "contents.")
                                                     (string/includes? (:file/path file) ".edn")
                                                     (string/includes? (:file/path file) "custom.css"))) non-journals)]
    (concat (reverse journals) built-in others)))
