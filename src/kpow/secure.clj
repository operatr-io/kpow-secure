(ns kpow.secure
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [kpow.secure.key :as key])
  (:import (java.io StringReader)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.security SecureRandom)
           (javax.crypto SecretKey Cipher)
           (javax.crypto.spec IvParameterSpec)
           (java.util Base64 Properties))
  (:gen-class))

(def kpow-secure-key "KPOW_SECURE_KEY")

;; scheme version static as v1 for now and encoded into the message as first byte
(def scheme-v1 (unchecked-byte 1))
(def cipher-algorithm "AES/CBC/PKCS5Padding")

(defn random-iv
  "Generate a 16-byte random IvParameterSpec"
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (IvParameterSpec. bytes)))

(defn cipher-bytes
  "Produce cipher-text from key / iv / plain-text input"
  [^SecretKey secret-key ^IvParameterSpec iv-spec ^String plain-text]
  (let [cipher (Cipher/getInstance cipher-algorithm)]
    (.init cipher Cipher/ENCRYPT_MODE secret-key ^IvParameterSpec iv-spec)
    (.doFinal cipher (.getBytes plain-text (.name StandardCharsets/UTF_8)))))

(defn plain-text
  "Produce plain-text from key / iv / cipher-bytes input"
  [^SecretKey secret-key ^IvParameterSpec iv-spec cipher-bytes]
  (let [cipher (Cipher/getInstance cipher-algorithm)]
    (.init cipher Cipher/DECRYPT_MODE secret-key ^IvParameterSpec iv-spec)
    (String. (.doFinal cipher cipher-bytes) (.name StandardCharsets/UTF_8))))

(defn encoded-payload
  "Produce a payload with format:
    * 1 byte scheme version (currently hard-coded to '1')
    * 1 byte initialization vector length (v1 scheme expects 16 bytes)
    * Initialization vector of size ^
    * Cipher text"
  [^SecretKey secret-key ^String plain-text]
  (let [payload-iv           (random-iv)
        payload-iv-bytes     (.getIV ^IvParameterSpec payload-iv)
        payload-bytes        (cipher-bytes secret-key payload-iv plain-text)
        payload-iv-length    (count payload-iv-bytes)
        payload-bytes-length (count payload-bytes)
        buffer               (ByteBuffer/allocate (+ 1 1 payload-iv-length payload-bytes-length))]
    (.put buffer (byte-array [scheme-v1]))
    (.put buffer (byte-array [(unchecked-byte payload-iv-length)]))
    (.put buffer ^"[B" payload-iv-bytes)
    (.put buffer ^"[B" payload-bytes)
    (String. (.encode (Base64/getEncoder) (.array buffer)) StandardCharsets/UTF_8)))

(defn decoded-text
  "Validate the payload parts, then produce plain-text original of input cipher-text"
  [^SecretKey secret-key ^String encoded-payload]
  (let [buffer          (->> (.getBytes encoded-payload StandardCharsets/UTF_8)
                             (.decode (Base64/getDecoder))
                             (ByteBuffer/wrap))
        message-version (unchecked-int (.get buffer))
        iv-length       (unchecked-int (.get buffer))]
    (when-not (= 1 message-version)
      (throw (IllegalArgumentException. (format "Invalid scheme version: %s" message-version))))
    (when-not (= 16 iv-length)
      (throw (IllegalArgumentException. (format "Invalid initialization vector size: %s" iv-length))))
    (let [iv-bytes (byte-array iv-length)]
      (.get buffer iv-bytes)
      (let [cypher-bytes (byte-array (.remaining buffer))]
        (.get buffer cypher-bytes)
        (plain-text secret-key (IvParameterSpec. iv-bytes) cypher-bytes)))))

(defn env-key
  "Retrieve an encoded encryption key from the kpow-secure-key environment variable"
  []
  (System/getenv kpow-secure-key))

(defn encrypted
  ([plain-text]
   (encrypted (env-key) plain-text))
  ([key-text plain-text]
   (when (str/blank? key-text)
     (throw (IllegalArgumentException. "No key provided")))
   (when (nil? plain-text)
     (throw (IllegalArgumentException. "No key provided")))
   (encoded-payload (key/import-key key-text) plain-text)))

(defn decrypted
  ([payload-text]
   (decrypted (env-key) payload-text))
  ([key-text payload-text]
   (when (str/blank? key-text)
     (throw (IllegalArgumentException. "No key provided")))
   (when (str/blank? payload-text)
     (throw (IllegalArgumentException. "No payload provided")))
   (decoded-text (key/import-key key-text) payload-text)))

(defn file-text
  [file]
  (when file
    (try
      (slurp file)
      (catch Exception ex
        (throw (ex-info (str "File not found: %s" file) {} ex))))))

(defn text-file
  [text file encrypt?]
  (try
    (spit file text)
    (log/info "\n\nKpow %s:\n---------------\n\n> %s" (if encrypt? "Encrypted" "Decrypted") file)
    (catch Exception ex
      (throw (ex-info (str "Could not write to: %s" file) {} ex)))))

(defn log-text
  [text encrypt?]
  (log/info "\n\nKpow %s:\n---------------\n\n%s" (if encrypt? "Encrypted" "Decrypted") text))

(defn process
  [encrypt? key-text target-text out-file]
  (let [text     (if encrypt? (encrypted key-text target-text) (decrypted key-text target-text))]
    (if out-file
      (text-file text out-file encrypt?)
      (log-text text encrypt?))))

(defn ->props
  [text]
  (let [props (Properties.)]
    (.load props (StringReader. text))
    props))

(defn ->map
  [text]
  (into {} (->props text)))

(def cli-options
  [[nil "--key TEXT" "Base64 encoded key"]
   [nil "--key-file FILE" "File containing base64 encoded key"]
   [nil "--encrypt TEXT" "Text to encrypt"]
   [nil "--decrypt TEXT" "Base64 encoded payload text"]
   [nil "--encrypt-file FILE" "File containing text to encrypt"]
   [nil "--decrypt-file FILE" "File containing base64 encoded payload text"]
   [nil "--out-file FILE" "(optional) File for encrypted/decrypted output"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-options)
        {:keys [key key-file encrypt decrypt encrypt-file decrypt-file out-file help]} options]
    (try
      (let [key-text    (or key (file-text key-file))
            target-file (or encrypt-file decrypt-file)
            target-text (or encrypt decrypt (file-text target-file))]
        (cond
          errors (log/error (str "\n\n" errors))
          (or help (empty? options)) (log/info (str "\n\n" summary))
          (str/blank? key-text) (log/info "\n\nRequired: --key, or --key-file")
          (str/blank? target-text) (log/info "\n\nRequired --encrypt, --decrypt, --encrypt-file, or --decrypt-file")
          :else (process (or encrypt encrypt-file) key-text target-text out-file)))
      (catch Exception ex
        (log/error ex)))))