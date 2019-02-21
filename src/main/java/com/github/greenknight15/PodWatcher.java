package com.github.greenknight15;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

class PodWatcher {

    private static final List<Watch<V1Pod>> watchers = new ArrayList<>();
    private static CoreV1Api api;
    private static final ExecutorService executorService = Executors.newFixedThreadPool(100);
    private static final Map<String, Future> terminatingPods = new HashMap<>();
    private static final int timeToWait = (System.getenv("WAIT_DURATION")!=null) ? Integer.parseInt(System.getenv("WAIT_DURATION")) : 30;
    private static String[] namespaces = System.getenv("NAMESPACE") != null ? System.getenv("NAMESPACE").split(",") : "james,default,default".split(",");
    private static final String labels = System.getenv("REQUIRE_LABEL") != null ? System.getenv("LABELS") : null;

    public static void main(String[] args) throws ApiException{
        try {
            //If false use kubeconfig else use serviceaccount
            boolean inCluster = false;
            ApiClient client;
            if (inCluster) {
                client = Config.fromCluster();
            } else {
                client = Config.fromConfig(System.getenv("USERPROFILE")+"/.kube/config");
            }
            client.getHttpClient().setReadTimeout(0, TimeUnit.MILLISECONDS);
            Configuration.setDefaultApiClient(client);
            api = new CoreV1Api();

            if(namespaces == null) {
                System.out.print("Watching all namespaces");
                Watch<V1Pod> watch = Watch.createWatch(
                        client,
                        api.listPodForAllNamespacesCall(null, null, null, labels, 56, null, null, null, Boolean.TRUE, null, null),
                        new TypeToken<Watch.Response<V1Pod>>() {
                        }.getType());
                watchers.add(watch);
                executorService.submit(new podWatchTask(watch));
            } else {
                namespaces = Arrays.stream(namespaces).distinct().toArray(String[]::new);
                for (String namespace : namespaces) {
                    System.out.printf("%nWatching  namespace: %s", namespace);
                    Watch<V1Pod> watch = Watch.createWatch(
                            client,
                            api.listNamespacedPodCall(namespace, null, null, null, false, labels, 56, null, null, Boolean.TRUE, null, null),
                            new TypeToken<Watch.Response<V1Pod>>() {
                            }.getType());
                    watchers.add(watch);
                    executorService.submit(new podWatchTask(watch));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class podWatchTask implements Runnable {
        private final Watch<V1Pod> watch;

        private podWatchTask(Watch<V1Pod> watch) {
            this.watch = watch;
        }

        @Override
        public void run() {
            for (Watch.Response<V1Pod> item : watch) {
                //V1PodStatus podStatus = item.object.getStatus();
                String name = item.object.getMetadata().getName();
                String namespace = item.object.getMetadata().getNamespace();
                boolean isTerminating = item.object.getMetadata().getDeletionTimestamp() != null;
                //System.out.printf("%nName: %s",name);

                if(isTerminating && !terminatingPods.containsKey(name)) {
                    Future f = executorService.submit(new runTimer(name,namespace));
                    terminatingPods.put(name,f);
                    System.out.printf("%nPending termination: %s",name);
                }

                if(item.type.equals("DELETED")) {
                    System.out.printf("%nDeleted pod %s",name);
                    Future f = terminatingPods.get(name);

                    if(f != null) {

                        if (f.isCancelled()) {
                            System.out.printf("%nTimer canceled: %s", name);
                        } else if(f.isDone()) {
                            System.out.printf("%nTimer done: %s", name);
                        }
                        f.cancel(true);
                        System.out.printf("%nTimer canceled: %s", name);

                        terminatingPods.remove(name);
                        System.out.printf("%nRemoved from queue: %s", name);
                        System.out.printf("%nPods left in queue: %s", terminatingPods.keySet().toString());
                    } else {
                        System.out.printf("%nTimer not found: %s", terminatingPods.keySet().toString());
                    }
                }
            }
        }
    }

    private static class runTimer implements Runnable {
        private final String name;
        private final String namespace;

        private runTimer(String name, String namespace) {
            this.name = name;
            this.namespace =namespace;
        }
        @Override
        public void run() {
            System.out.printf("%nStarting timer: %s", name);

            Calendar c = Calendar.getInstance();
            c.setTime(new Date()); // Now use today date.
            c.add(Calendar.SECOND, timeToWait);

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        System.out.printf("%nTimes up, force delete %s", name);
                        V1DeleteOptions body = new V1DeleteOptions(); // V1DeleteOptions |
                        body.setGracePeriodSeconds((long)0);
                        String pretty = "true"; // String | If 'true', then the output is pretty printed.
                        Integer gracePeriodSeconds = 0; // Integer | The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
                        String propagationPolicy = "Foreground"; // String | Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.

                        V1Status result = api.deleteNamespacedPod(name, namespace, body, pretty,gracePeriodSeconds, true, propagationPolicy);
                        System.out.println(result);
                    } catch (ApiException e) {
                        System.err.println("Exception when calling CoreV1Api#deleteNamespacedPod");
                        e.printStackTrace();
                    }
                    catch (JsonSyntaxException e) {
                        if (e.getCause() instanceof IllegalStateException) {
                            IllegalStateException ise = (IllegalStateException) e.getCause();
                            if (ise.getMessage() != null && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT"))
                                System.out.printf("%nCatching exception because of issue https://github.com/kubernetes-client/java/issues/86");
                            else throw e;
                        } else throw e;
                    }
                    terminatingPods.remove(name);
                }
            }, c.getTime());
        }
    }
}