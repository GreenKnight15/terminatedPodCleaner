package com.github.greenknight15;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PodWatcher {

    private static final int timeToWait = (System.getenv("WAIT_DURATION") != null) ? Integer.parseInt(System.getenv("WAIT_DURATION")) : 300;
    private static final String[] namespaces = System.getenv("NAMESPACES") != null ? System.getenv("NAMESPACES").split(",") : null;
    private static final String labels = System.getenv("REQUIRE_LABEL") != null ? System.getenv("LABELS") : null;

    private static final ExecutorService executorService = Executors.newFixedThreadPool(100);
    private static final Map<String, Timer> terminatingPods = new HashMap<>();

    private static final Logger logger = Logger.getLogger(PodWatcher.class);

    private static CoreV1Api api;
    private static ApiClient client;

    public static void main(String[] args) throws ApiException{
        try {
            client = Config.defaultClient();
            client.getHttpClient().setReadTimeout(0, TimeUnit.MILLISECONDS);
            Configuration.setDefaultApiClient(client);

            api = new CoreV1Api();

            if(namespaces == null) {
                logger.info("Watching all namespaces");
                Watch<V1Pod> watch = watchPodsForAllNamespaces(labels);
                executorService.submit(new podWatchTask(watch));
            } else {
                String[] distinctNamespaces = Arrays.stream(namespaces).distinct().toArray(String[]::new);
                for (String namespace : distinctNamespaces) {
                    logger.info("Watching  namespace: " + namespace);
                    Watch<V1Pod> watch = watchPodsInNamespace(namespace, labels);
                    executorService.submit(new podWatchTask(watch));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static TimerTask forceTerminatePodTimer(String name, String namespace) {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    logger.info("Times up, force delete " + name);
                    V1DeleteOptions body = new V1DeleteOptions(); // V1DeleteOptions |
                    body.setGracePeriodSeconds((long) 0);
                    String pretty = "true"; // String | If 'true', then the output is pretty printed.
                    Integer gracePeriodSeconds = 0; // Integer | The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
                    String propagationPolicy = "Foreground"; // String | Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.

                    V1Status result = api.deleteNamespacedPod(name, namespace, body, pretty, gracePeriodSeconds, true, propagationPolicy);
                    System.out.println(result);
                } catch (ApiException e) {
                    logger.error("Exception when calling CoreV1Api#deleteNamespacedPod", e);
                } catch (JsonSyntaxException e) {
                    if (e.getCause() instanceof IllegalStateException) {
                        IllegalStateException ise = (IllegalStateException) e.getCause();
                        if (ise.getMessage() != null && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT"))
                            logger.error("Catching exception because of issue https://github.com/kubernetes-client/java/issues/86");
                        else throw e;
                    } else throw e;
                }
                terminatingPods.remove(name);
            }
        };
    }

    private static Watch<V1Pod> watchPodsForAllNamespaces(String labels) throws ApiException {
        return Watch.createWatch(
                client,
                api.listPodForAllNamespacesCall(null,
                        null,
                        null,
                        labels,
                        56,
                        null,
                        null,
                        null,
                        Boolean.TRUE,
                        null,
                        null
                ),
                new TypeToken<Watch.Response<V1Pod>>() {
                }.getType());
    }

    private static Watch<V1Pod> watchPodsInNamespace(String namespace, String labels) throws ApiException {
        logger.info("Watching  namespace: " + namespace);
        return Watch.createWatch(
                client,
                api.listNamespacedPodCall(namespace,
                        null,
                        null,
                        null,
                        false,
                        labels,
                        56,
                        null,
                        null,
                        Boolean.TRUE,
                        null,
                        null
                ),
                new TypeToken<Watch.Response<V1Pod>>() {
                }.getType());
    }

    private static class podWatchTask implements Runnable {
        private final Watch<V1Pod> watch;

        private podWatchTask(Watch<V1Pod> watch) {
            this.watch = watch;
        }

        @Override
        public void run() {
            for (Watch.Response<V1Pod> item : watch) {
                String name = item.object.getMetadata().getName();
                String namespace = item.object.getMetadata().getNamespace();
                boolean isTerminating = item.object.getMetadata().getDeletionTimestamp() != null;

                if (isTerminating && !terminatingPods.containsKey(name)) {
                    Calendar c = Calendar.getInstance();
                    Date now = new Date();
                    c.setTime(now); // Now use today date.
                    c.add(Calendar.SECOND, timeToWait);
                    logger.debug("Starting timer: " + now.toString() + " if stuck will terminate at " + c.getTime().toString());
                    Timer timer = new Timer();
                    timer.schedule(forceTerminatePodTimer(name, namespace), c.getTime());
                    terminatingPods.put(name, timer);
                }

                if (item.type.equals("DELETED")) {
                    logger.info("Deleted pod " + name);
                    Timer timer = terminatingPods.get(name);
                    if (timer != null) {
                        timer.cancel();
                        logger.debug("Timer canceled: " + name);
                        terminatingPods.remove(name);
                        logger.debug("Removed from queue: " + name);
                        logger.trace("Pods left in queue: " + terminatingPods.keySet().toString());
                    } else {
                        logger.debug("Timer not found: " + terminatingPods.keySet().toString());
                    }
                }
            }
        }
    }
}