package com.github.greenknight15;

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

public class PodWatcher {

    private static Watch<V1Pod> watch;
    private static ApiClient client;
    private static CoreV1Api api;
    private static ExecutorService executorService = Executors.newFixedThreadPool(100);
    private  static Map<String, Future> terminatingPods = new HashMap<>();

    public static void main(String[] args) throws IOException, ApiException{
        try {
            boolean inCluster = false;
            if (inCluster) {
                client = Config.fromCluster();
            } else {
                client = Config.fromConfig("C:/Users/James/.kube/config");
            }
            client.getHttpClient().setReadTimeout(0, TimeUnit.MILLISECONDS);
            Configuration.setDefaultApiClient(client);
            api = new CoreV1Api();
            watch = Watch.createWatch(
                    client,
                    api.listPodForAllNamespacesCall(null, null, null, null, 5, null, null, null,Boolean.TRUE, null, null),
                    new TypeToken<Watch.Response<V1Pod>>() {
                    }.getType());
            executorService.submit(podWatchTask);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Runnable podWatchTask = new Runnable() {
        @Override
        public void run() {
            for (Watch.Response<V1Pod> item : watch) {
                V1PodStatus podStatus = item.object.getStatus();
                String name = item.object.getMetadata().getName();
                String status = podStatus.getPhase();
                String kind = item.object.getKind();
                String details = podStatus.toString();
                String phase = podStatus.getPhase();
                boolean isTerminating = item.object.getMetadata().getDeletionTimestamp() != null;
                details = "";
                if(isTerminating) {

                    if(!terminatingPods.containsKey(name)) {
                        Future f = executorService.submit(new doStuff(name));
                        terminatingPods.put(name,f);
                        System.out.printf("%nPending Termination: %s",name);
                    }
                }
                switch (item.type) {
                        case "DELETED":
                            System.out.printf("%nCancel pending termination: %s",name);
                            Future f = terminatingPods.get(name);
                            if(f != null) {
                                f.cancel(true);
                                System.out.printf("%nIs future terminated?: %s", f.isCancelled());
                                terminatingPods.remove(name);
                                System.out.printf("%n%s REMOVED from queue", name);
                                System.out.printf("%nDELETE ME: %s", terminatingPods.keySet().toString());
                            }
                            break;
                }
            };
        }
    };

    static class doStuff implements Runnable {
        private boolean stop = false;
        private final String podName;

        public doStuff(String podName) {
            this.podName = podName;
        }

        @Override
        public void run(){
            Calendar current = Calendar.getInstance();
            Calendar timesUp = Calendar.getInstance();
            timesUp.add(Calendar.SECOND,10);
            System.out.printf("%nStart the clock for pod: %s | Start: %s | End: %s",podName,current.getTime().toString(),timesUp.getTime().toString());

            while(!stop && current.before(timesUp)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    System.out.printf("%n%s",e.getMessage());
                }
                current = Calendar.getInstance();
            }
            if(stop) {
                return;
            } else {
                System.out.printf("%nTimes up, force delete %s", podName);
                this.stop = true;
                try {
                    V1DeleteOptions deleteOptions = new V1DeleteOptions();
                    deleteOptions.setGracePeriodSeconds((long) 0);
                    api.deleteNamespacedPod(podName,"default",deleteOptions,null,0,null,null);
                } catch (ApiException e) {
                    e.printStackTrace();
                }
                terminatingPods.remove(podName);
            }
        }
    }
}