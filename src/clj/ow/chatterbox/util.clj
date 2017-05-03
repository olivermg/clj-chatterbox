(ns ow.chatterbox.util)

(defn random-string [len]
  (let [alphabet (str "abcdefghijklmnopqrstuvwxyz"
                      "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                      "0123456789")]
    (->> #(rand-nth alphabet)
         repeatedly
         (take len)
         (apply str))))
