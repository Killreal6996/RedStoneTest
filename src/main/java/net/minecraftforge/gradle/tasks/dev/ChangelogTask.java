package net.minecraftforge.gradle.tasks.dev;

import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import groovy.lang.Closure;
import groovy.util.MapEntry;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedString;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;

public class ChangelogTask extends DefaultTask {
    @Input
    DelayedString serverRoot;

    @Input
    DelayedString jobName;

    @Input
    DelayedString authName;

    @Input
    DelayedString authPassword;

    @Input
    Closure<String> targetBuild;
    private int targetBuildResolved = -1;

    @OutputFile
    DelayedFile output;

    private String auth = null;

    @SuppressWarnings("unchecked")
    @TaskAction
    public void doTask() throws IOException {
        if (getAuthName() != null && getAuthPassword() != null) {
            String raw = getAuthName() + ":" + getAuthPassword();
            auth = "Basic " + new String(Base64.getEncoder().encode(raw.getBytes()), StandardCharsets.ISO_8859_1);
        }

        List<Map<String, Object>> builds = getBuildInfo();
        getLatestBuild(builds);

        StringBuilder out = new StringBuilder();
        out.append("Changelog:\r\n");
        for (Map<String, Object> build : builds) {
            int number = ((Double) build.get("number")).intValue();
            List<MapEntry> items = (List<MapEntry>) build.get("items");

            if (getTargetBuild() > 0 && number > getTargetBuild()) continue;

            out.append("Build ");
            out.append(build.get("version") == null ? number : build.get("version"));
            out.append(':').append('\n');

            for (MapEntry entry : items) {
                String[] lines = ((String) entry.getValue()).trim().split("\n");
                if (lines.length == 1) {
                    out.append('\t').append(entry.getKey()).append(": ").append(lines[0]).append('\n');
                } else {
                    out.append('\t').append(entry.getKey()).append(':').append('\n');
                    for (String line : lines) {
                        out.append('\t').append('\t').append(line).append('\n');
                    }
                }
            }
            out.append('\n');
        }

        Files.write(getOutput().toPath(), out.toString().getBytes());
        getProject().getArtifacts().add("archives", getOutput());
    }

    private String read(String url) throws IOException, URISyntaxException {
        return read(new URI(getServerRoot() + "job/" + getJobName() + url));
    }

    private String read(URI url) throws IOException {
        URLConnection con = url.toURL().openConnection();
        con.setRequestProperty("User-Agent", Constants.USER_AGENT);
        if (auth != null) {
            getLogger().debug(auth);
            con.addRequestProperty("Authorization", auth);
        }
        return new String(ByteStreams.toByteArray(con.getInputStream()));
    }

    private String cleanJson(String data, String part) {
        data = data.replace("," + part + ",", ",");
        data = data.replace(part + ",", "");
        data = data.replace("," + part, "");
        data = data.replace("{" + part + "}", "{}");
        data = data.replace("[" + part + "]", "[]");
        return data;
    }

    private static final Gson GSON_FORMATTER = new GsonBuilder().setPrettyPrinting().create();

    @SuppressWarnings("unused")
    private void prettyPrint(Object json) {
        getLogger().lifecycle(GSON_FORMATTER.toJson(json));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getBuildInfo() {
        String data = null;
        try {
            boolean versioned = false;
            data = read("/api/python?tree=allBuilds[result,number,actions[text],changeSet[items[author[fullName],comment]]]");//&pretty=true");
            data = data.replace("\"result\":None", "\"result\":\"\"");
            data = cleanJson(data, "None"); //This kills Gson for some reason
            data = cleanJson(data, "{}"); //Empty entries, just for sanities sake

            List<Map<String, Object>> json = (List<Map<String, Object>>) new Gson().fromJson(data, Map.class).get("allBuilds");
            json.sort((o1, o2) -> (int) ((Double) o1.get("number") - (Double) o2.get("number")));

            List<Entry<String, String>> items = new ArrayList<>();
            Iterator<Map<String, Object>> bitr = json.iterator();
            while (bitr.hasNext()) {
                Map<String, Object> build = bitr.next();

                List<Map<String, String>> actions = (List<Map<String, String>>) build.get("actions");
                actions.removeIf(map -> !map.containsKey("text") || map.get("text").contains("http"));

                if (actions.size() == 0) {
                    build.put("version", versioned ? ((Double) build.get("number")).intValue() : getProject().getVersion());
                    versioned = true;
                } else {
                    build.put("version", actions.get(0).get("text"));
                }

                for (Map<String, Object> e : (List<Map<String, Object>>) ((Map<String, Object>) build.get("changeSet")).get("items")) {
                    items.add(new MapEntry(((Map<String, String>) e.get("author")).get("fullName"), e.get("comment")));
                }
                build.put("items", items);

                if (build.get("result").equals("SUCCESS")) {
                    if (items.size() == 0) bitr.remove();
                    items = new ArrayList<>();
                } else {
                    bitr.remove();
                }

                build.remove("result");
                build.remove("changeSet");
                build.remove("actions");
            }
            //prettyPrint(json);
            json.sort((o1, o2) -> (int) ((Double) o2.get("number") - (Double) o1.get("number")));
            return json;
        } catch (Exception e) {
            e.printStackTrace();
            getLogger().lifecycle(data);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private void getLatestBuild(List<Map<String, Object>> builds) {
        String data = null;
        try {
            Object ver = "";
            if (builds.size() > 0) {
                ver = builds.get(0).get("number");
            }
            boolean versioned = false;
            data = read("/lastBuild/api/python?tree=number,changeSet[items[author[fullName],comment]]");//&pretty=true");
            data = cleanJson(data, "None"); //This kills Gson for some reason
            data = cleanJson(data, "{}"); //Empty entries, just for sanities sake

            Map<String, Object> build = (Map<String, Object>) new Gson().fromJson(data, Map.class);
            if (build.get("number").equals(ver)) {
                return;
            }
            build.put("version", versioned ? "Build " + ((Double) build.get("number")).intValue() : getProject().getVersion());

            List<Entry<String, String>> items = new ArrayList<>();
            for (Map<String, Object> e : (List<Map<String, Object>>) ((Map<String, Object>) build.get("changeSet")).get("items")) {
                items.add(new MapEntry(((Map<String, String>) e.get("author")).get("fullName"), e.get("comment")));
            }
            build.put("items", items);

            build.remove("result");
            build.remove("changeSet");
            build.remove("actions");

            builds.add(0, build);
            //prettyPrint(build);
        } catch (Exception e) {
            e.printStackTrace();
            getLogger().lifecycle(data);
        }
    }

    public String getServerRoot() {
        return serverRoot.call();
    }

    public void setServerRoot(DelayedString serverRoot) {
        this.serverRoot = serverRoot;
    }

    public String getJobName() {
        return jobName.call();
    }

    public void setJobName(DelayedString jobName) {
        this.jobName = jobName;
    }

    public String getAuthName() {
        return authName.call();
    }

    public void setAuthName(DelayedString authName) {
        this.authName = authName;
    }

    public String getAuthPassword() {
        return authPassword.call();
    }

    public void setAuthPassword(DelayedString authPassword) {
        this.authPassword = authPassword;
    }

    public int getTargetBuild() {
        if (targetBuildResolved != -1) {
            return targetBuildResolved;
        }

        targetBuildResolved = Integer.MAX_VALUE;
        if (targetBuild != null) {
            try {
                targetBuildResolved = Integer.parseInt(targetBuild.call());
                if (targetBuildResolved <= 0) {
                    targetBuildResolved = Integer.MAX_VALUE;
                }
            } catch (NumberFormatException e) {
                getLogger().debug("Error reading target build: " + e.getMessage());
            }
        }

        return targetBuildResolved;
    }

    public void setTargetBuild(Closure<String> targetBuild) {
        this.targetBuild = targetBuild;
    }

    public File getOutput() {
        return output.call();
    }

    public void setOutput(DelayedFile output) {
        this.output = output;
    }
}
