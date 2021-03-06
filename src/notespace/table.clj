(ns notespace.table
  "Interactive table visualisations."
  (:require [hiccup.core :as hiccup]
            [hiccup.element :as elem]
            [garden.core :as garden]
            [jsonista.core :as jsonista]
            [notespace.cdn :as cdn]
            [notespace.io :refer [view-html!]])
  (:import java.io.File
           java.util.UUID))

(defn row-vectors->table-hiccup [column-names row-vectors]
  [:table
   [:thead (into [:tr] (map (fn [x] [:th x])
                                  column-names))]
   [:tbody (map #(into [:tr]
                      (map (fn [x] [:td x]) %))
                row-vectors)]])

(defn row-maps->table-hiccup
  ([row-maps]
   (-> row-maps
       first
       keys
       (row-maps->table-hiccup row-maps)))
  ([column-names row-maps]
   (if column-names
     (->> row-maps
          (map (fn [row] ; Actually row can be a record, too.
                 (map #(get row %) column-names)))
          (row-vectors->table-hiccup column-names))
     ;; else
     (row-maps->table-hiccup row-maps))))

(def datatables-default-options
  {:sPaginationType "full_numbers"})

(defn datatables-js [datatables-options table-id]
  (->> (str "$(function() {
               $(\"#" table-id "\").DataTable("
            (jsonista/write-value-as-string
             (merge datatables-default-options
                    datatables-options))
                ");"
            "});")))

(def datatables-css
  (garden/css [:.even {}]
              [:.odd {}]))

(defn table-hiccup->table-html
  ([table-hiccup]
   (table-hiccup->table-html "" table-hiccup))
  ([id table-hiccup]
   (hiccup/html
    [:head
     [:style datatables-css]]
    ;; Take a :table element with specific id and class:
    [:body
     [:table.stripe.dataTable {:id id}
      ;; Now take the givn table-hiccup without its :table prefix - we have already got one.
      (rest table-hiccup)]])))

(defn table-hiccup->datatables-html
  ([table-hiccup] (table-hiccup->datatables-html {} table-hiccup))
  ([datatables-options table-hiccup]
   (table-hiccup->datatables-html {} datatables-options table-hiccup))
  ([{:keys [cdn?]} datatables-options table-hiccup]
   (let [id (str (UUID/randomUUID))]
     (->> (str (when cdn?
                 (->> :datatables
                      cdn/header
                      (into [:div])
                      hiccup/html))
               (table-hiccup->table-html id table-hiccup)
               (->> (datatables-js datatables-options
                                   id)
                    elem/javascript-tag
                    hiccup/html))))))

(defn ->datatable
  ([row-maps]
   (->> row-maps
        (->datatable {})))
  ([datatables-options row-maps]
   (->> row-maps
        notespace.table/row-maps->table-hiccup
        (notespace.table/table-hiccup->datatables-html datatables-options))))

