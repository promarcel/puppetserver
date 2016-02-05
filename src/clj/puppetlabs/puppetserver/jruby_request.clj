(ns puppetlabs.puppetserver.jruby-request
  (:require [clojure.tools.logging :as log]
            [ring.util.response :as ring-response]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]))

(defn throw-bad-request!
  "Throw a ::bad-request type slingshot error with the supplied message"
  [message]
  (sling/throw+ {:type ::bad-request
                 :message message}))

(defn bad-request?
  [x]
  "Determine if the supplied slingshot message is for a 'bad request'"
  (when (map? x)
    (= (:type x)
       :puppetlabs.puppetserver.jruby-request/bad-request)))

(defn service-unavailable?
  [x]
  "Determine if the supplied slingshot message is for a 'service unavailable'"
  (when (map? x)
    (= (:type x)
       :puppetlabs.services.jruby.jruby-puppet-service/service-unavailable)))

(defn jruby-timeout?
  "Determine if the supplied slingshot message is for a JRuby borrow timeout."
  [x]
  (when (map? x)
    (= (:type x)
       :puppetlabs.services.jruby.jruby-puppet-service/jruby-timeout)))

(defn output-error
  [{:keys [uri]} {:keys [message]} http-status]
  (log/errorf "Error %d on SERVER at %s: %s" http-status uri message)
  (-> (ring-response/response message)
      (ring-response/status http-status)
      (ring-response/content-type "text/plain")))

(defn wrap-with-error-handling
  "Middleware that wraps a JRuby request with some error handling to return
  the appropriate http status codes, etc."
  [f]
  (fn [request]
    (sling/try+
     (f request)
     (catch bad-request? e
       (output-error request e 400))
     (catch jruby-timeout? e
       (output-error request e 503))
     (catch service-unavailable? e
       (output-error request e 503)))))

(defn wrap-with-jruby-instance
  "Middleware fn that borrows a jruby instance from the `jruby-service` and makes
  it available in the request as `:jruby-instance`"
  [f jruby-service]
  (fn [request]
    (jruby/with-jruby-puppet
     jruby-instance
     jruby-service
     {:request (dissoc request :ssl-client-cert)}

     (f (assoc request :jruby-instance jruby-instance)))))

(defn get-environment-from-request
  "Gets the environment from a request."
  [req]
  (-> req
      (get-in [:params "environment"])))

(defn validate-environment-fn
  "Middleware function which validates the presence and syntactical content
  of an environment in a ring request.  If validation fails, a ::bad-request
  slingshot exception is thrown.  If validation succeeds, the request is
  threaded through to the supplied 'f' function."
  [f]
  (fn [request]
    (let [environment (get-environment-from-request request)]
      (cond
        (nil? environment)
        (throw-bad-request!
         "An environment parameter must be specified")

        (not (re-matches #"^\w+$" environment))
        (throw-bad-request!
         (str
          "The environment must be purely alphanumeric, not '"
          environment
          "'"))

        :else
        (f request)))))
