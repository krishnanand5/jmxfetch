package org.datadog.supervisor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import org.datadog.Defaults;

import lombok.extern.slf4j.Slf4j;

import io.javalin.*;

class AppConfig {
    public int controlHttpPort;
    public String rmiHostname;

    public AppConfig() {
        String controlHttpPortStr = System.getenv("SUPERVISOR_PORT");
        this.rmiHostname = null;
        if (controlHttpPortStr != null) {
            this.controlHttpPort = Integer.parseInt(controlHttpPortStr);
        } else {
            this.controlHttpPort = 8088;
        }
    }
}

class SupervisorInitSpec {
    public String rmiHostname;
}

@Slf4j
public class App {
    private static Process process = null;
    // marked when OS process is started
    private static AtomicBoolean running = new AtomicBoolean(false);
    // marked when the JMX server indicates that its ready
    private static AtomicBoolean started = new AtomicBoolean(false);
    private static final String jmxServerEntrypoint = "org.datadog.misbehavingjmxserver.App";
    private static String selfJarPath;
    private static AppConfig config;

    public static void main(String[] args) throws IOException {
        App.config = new AppConfig();

        try {
            selfJarPath = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath())
                    .getAbsolutePath();
        } catch (Exception e) {
            log.warn("Could not get self jar path");
            e.printStackTrace();
            System.exit(1);
        }

        Javalin app = Javalin.create();

        app.post("/init/", ctx -> {
            SupervisorInitSpec initSpec;
            try {
                initSpec = ctx.bodyAsClass(SupervisorInitSpec.class);
            } catch (Exception e) {
                log.error("JSON Deserialization error: ", e);
                ctx.status(400).result("Invalid JSON format");
                return;
            }
            App.config.rmiHostname = initSpec.rmiHostname;

            log.info("Got initialization settings, setting RMI Hostname to {}. Starting JMX Server now...", App.config.rmiHostname);
            try {
                startJMXServer();
                ctx.result("Init Completed. JMX Server is running").status(200);
            } catch (Exception e) {
                ctx.result("Error starting JMX Server: " + e.getMessage()).status(500);
            }
        });

        app.events(event -> {
            event.serverStarting(() -> {
                log.info("Supervisor HTTP control interface starting at :{}", App.config.controlHttpPort);
            });
            event.serverStarted(() -> {
                log.info("Supervisor HTTP Server Started. Waiting for initialization payload POST to /init");
            });
        });

        app.get("/ready", ctx -> {
            if (started.get()) {
                ctx.status(200);
            } else {
                ctx.status(500);
            }
        });


        app.start(App.config.controlHttpPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            if (running.get()) {
                log.info("Stopping the sub-process....");
                try {
                    stopJMXServer();
                } catch (IOException | InterruptedException e) {
                    log.error("Encountered error while shutting down JMX server: ", e);
                }
            }
        }));
    }

    static void stopJMXServer() throws IOException, InterruptedException {
        if (process != null) {
            process.destroyForcibly().waitFor();
            running.set(false);
            started.set(false);
        }
    }

    static void startJMXServer() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("java",
            "-cp",
            selfJarPath,
            jmxServerEntrypoint,
            "--rmi-host",
            App.config.rmiHostname);
        pb.inheritIO();
        process = pb.start();
        running.set(true);

        try {
            // Block until the JMXServer is ready
            checkForJMXServerReady();
        } catch (IOException | InterruptedException e) {
            log.error("Error while waiting for JMX server to be ready: ", e);
        }
    }

    private static int getJMXServerControlPort() {
        String env = System.getenv("CONTROL_PORT");
        if (env == null) {
            return Defaults.JMXSERVER_CONTROL_PORT;
        }
        return Integer.parseInt(env);
    }

    private static void checkForJMXServerReady() throws MalformedURLException, IOException, InterruptedException {
        int delayBetweenAttemptsInMs = 100;
        int timeoutInMs = 2000;
        int elapsedMs = 0;
        URL endpointUrl = new URL("http://localhost:" + getJMXServerControlPort() + "/ready");
        while (!started.get() && elapsedMs < timeoutInMs) {
            HttpURLConnection connection = (HttpURLConnection) endpointUrl.openConnection();
            try {
                int responseCode = connection.getResponseCode();
                log.info("JMXServer returned code {}", responseCode);
                connection.disconnect();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("JMX server has started");
                    started.set(true);
                }
            } catch (ConnectException e) {
                // ignore
            }
            Thread.sleep(delayBetweenAttemptsInMs);
            elapsedMs += delayBetweenAttemptsInMs;
        }
    }
}
