package org.sonar.plugins.stash.fixtures;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class SonarQube {
    protected Path installDir;
    protected Process process;
    protected Properties config = new Properties();
    protected final static String PORT_PROPERTY = "sonar.web.port";
    protected final static String HOST_PROPERTY = "sonar.web.host";

    public SonarQube(Path installDir, int port) {
        this.installDir = installDir;
        config.setProperty(PORT_PROPERTY, String.valueOf(port));
        config.setProperty(HOST_PROPERTY, "127.0.0.1");
    }

    public int getPort() {
        return Integer.parseInt(config.getProperty(PORT_PROPERTY));
    }

    public String getHost() {
        return config.getProperty(HOST_PROPERTY);
    }

    // This  method searches for the proper control script and returns the commandline to use
    protected String getExecutable(String action) {
        String os = System.getProperty("os.name");
        // In a windows context, we get more than we ask for (i.e. 'windows 7')
        if (os.toLowerCase().matches("windows.*")) {
            os = "windows";
        } else {
            os = os.toLowerCase();
        }

        String arch = System.getProperty("os.arch");
        if (arch.equals("amd64")) {
            arch = "x86-64";
        }

        String binary;
        if (os.equals("windows")) {
            if (action.equals("start")) {
                binary = "StartSonar.bat";
            } else if (action.equals("stop")) {
                binary = "StopNTService.bat"; // tentative "stop" action (may require more than this one or another one)
            } else {
                binary = "unknown_action.cmd"; // this will make the code fail early
            }
        } else {
            binary =  "sonar.sh " + action;
        }

        File exec = installDir.resolve("bin").resolve(os + "-" + arch).resolve(binary).toFile();

        if (!exec.exists()) {
            throw new IllegalArgumentException();
        }
        if (!exec.canExecute()) {
            throw new IllegalArgumentException();
        }
        return exec.toString();
    }

    public void setUp() {
        // noop
    }

    public Properties getConfig() {
        return config;
    }

    protected void writeConfig() throws Exception {
        File configFile = installDir.resolve("conf").resolve("sonar.properties").toFile();
        configFile.delete();
        try (OutputStream configStream = new FileOutputStream(configFile)) {
            config.store(configStream, null);
        }
    }

    public void startAsync() throws Exception {
        writeConfig();
        process = new ProcessBuilder(this.getExecutable("start"))
                .directory(installDir.toFile())
                .inheritIO()
                .start();
        if (process.waitFor() != 0) {
            throw new Exception();
        }
    }

    public void stop() throws Exception {
        new ProcessBuilder(this.getExecutable("stop"))
                .directory(installDir.toFile())
                .start().waitFor();
    }

    public void installPlugin(File sourceArchive) throws IOException {
        if (!sourceArchive.exists())
            throw new IllegalArgumentException();

        Files.copy(sourceArchive.toPath(),
                installDir.resolve("extensions").resolve("plugins").resolve(sourceArchive.toPath().getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
    }

    public void waitForReady() {
        AsyncHttpClient client = new AsyncHttpClient();
        while (true) {
            System.out.println("Waiting for SonarQube to be available at " + getUrl());
            Response response = null;
            try {
                response = client.prepareGet(getUrl().toString()).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                /* noop */
            }
            if (response != null && response.getStatusCode() == 200) {
                break;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                /* noop */
            }
        }
        System.out.println("SonarQube is ready");
    }

    public URL getUrl() {
        try {
            return new URL("http", getHost(), getPort(), "/");
        } catch (MalformedURLException e) {
            return null;
        }
    }

    public boolean createProject(String key, String name) throws IOException {
        URL url = new URL(getUrl(),
                "/api/projects/create?"
                + "key=" + URLEncoder.encode(key, "UTF-8")
                + "&"
                + "name=" + URLEncoder.encode(key, "UTF-8")
        );
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.connect();
        return (conn.getResponseCode() == 200);
    }
}
