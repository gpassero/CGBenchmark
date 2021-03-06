package fr.svivien.cgbenchmark.producerconsumer;

import fr.svivien.cgbenchmark.Constants;
import fr.svivien.cgbenchmark.api.CGPlayApi;
import fr.svivien.cgbenchmark.model.request.play.PlayRequest;
import fr.svivien.cgbenchmark.model.request.play.PlayResponse;
import fr.svivien.cgbenchmark.model.request.play.PlayResponse.Frame;
import fr.svivien.cgbenchmark.model.test.ResultWrapper;
import fr.svivien.cgbenchmark.model.test.TestInput;
import fr.svivien.cgbenchmark.model.test.TestOutput;
import okhttp3.OkHttpClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import retrofit2.Call;
import retrofit2.GsonConverterFactory;
import retrofit2.Retrofit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes tests in the broker, runs them against CG API and stores the results in synchronized collection
 */
public class Consumer implements Runnable {

    private static final Log LOG = LogFactory.getLog(Consumer.class);

    private String name;
    private Broker broker;
    private OkHttpClient client;
    private Retrofit retrofit;
    private CGPlayApi cgPlayApi;
    private ResultWrapper resultWrapper;
    private String cookie;
    private String ide;
    private int cooldown;
    private boolean saveLogs;

    private static final String outputFormat = "[ %10s ]%s";

    private AtomicBoolean pause;

    public Consumer(String name, Broker broker, String cookie, String ide, int cooldown, AtomicBoolean pause, boolean saveLogs) {
        this.cookie = cookie;
        this.ide = ide;
        this.name = name;
        this.broker = broker;
        this.pause = pause;
        this.client = new OkHttpClient.Builder().readTimeout(600, TimeUnit.SECONDS).build();
        this.retrofit = new Retrofit.Builder().client(client).baseUrl(Constants.CG_HOST).addConverterFactory(GsonConverterFactory.create()).build();
        this.cgPlayApi = retrofit.create(CGPlayApi.class);
        this.cooldown = cooldown;
        this.saveLogs = saveLogs;
    }

    @Override
    public void run() {
        long start = -1;
        try {
            while (true) {
                // Retrieves next test in the broker
                TestInput test = broker.getNextTest();

                // No more tests in the broker
                if (test == null) break;

                for (int tries = 0; tries < 20; tries++) { /** Arbitrary value .. */
                    start = System.currentTimeMillis();
                    TestOutput result = testCode(cgPlayApi, test);
                    LOG.info(String.format(outputFormat, this.name, result.getResultString()));
                    if (!result.isError()) {
                        resultWrapper.addTestResult(result);
                        break;
                    } else {
                        // Error occurred, waiting before retrying again
                        Thread.sleep(tries < 10 ? 20000 : 40000); /** More arbitrary values .. */
                    }
                }

                if (broker.getTestSize() > 0) {
                    shouldPause();

                    // The cooldown is applied on the start-time of each test, and not on the end-time of previous test
                    Thread.sleep(Math.max(100, cooldown * 1000 - (System.currentTimeMillis() - start)));

                    shouldPause();
                }
            }
            LOG.info("Consumer " + this.name + " finished its job.");
        } catch (InterruptedException ex) {
            LOG.fatal("Consumer " + name + " has encountered an issue.", ex);
        }
    }

    private void shouldPause() {
        if (pause.get()) {

            LOG.info(String.format(outputFormat, this.name, " -- PAUSED --"));
            while (pause.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    LOG.fatal("Consumer " + name + " has encountered an issue while resuming from pause", ex);
                }
            }
        }
    }

    private TestOutput testCode(CGPlayApi cgPlayApi, TestInput test) {
        PlayRequest request = new PlayRequest(test.getCode(), test.getLang(), ide, test.getSeed(), test.getPlayers());
        Call<PlayResponse> call = cgPlayApi.play(request, Constants.CG_HOST + "/ide/" + ide, cookie);
        try {
            PlayResponse playResponse = call.execute().body();
            if (saveLogs) dumpLogForPlay(test, playResponse);
            return new TestOutput(test, playResponse);
        } catch (IOException | RuntimeException e) {
            TestOutput to = new TestOutput(test, null);
            return to;
        }
    }

    private void dumpLogForPlay(TestInput test, PlayResponse response) {
        if (response.success == null) {
            // Nothing to log
            return;
        }

        // gameId as filename
        final String fileName = "." + File.separator + "logs" + File.separator + response.success.gameId + ".log";

        StringBuilder logStringBuilder = new StringBuilder();

        for (int iframe = 0; iframe < response.success.frames.size(); iframe++) {
            Frame currentFrame = response.success.frames.get(iframe);
            String logHeader = "----- " + iframe + " / " + response.success.frames.size() + " -----" + System.lineSeparator();

            if (currentFrame.error != null) { // Error frame
                logStringBuilder.append(logHeader);
                logStringBuilder.append("ERROR at line " + currentFrame.error.line + ":" + System.lineSeparator());
                logStringBuilder.append(currentFrame.error.message);
                logStringBuilder.append(System.lineSeparator());
            } else if (currentFrame.gameInformation.contains(Constants.TIMEOUT_INFORMATION_PART)) { // Timeout frame
                logStringBuilder.append(logHeader);
                logStringBuilder.append(test.getPlayers().get(currentFrame.agentId).getName() + " TIMEOUT !");
                logStringBuilder.append(System.lineSeparator());
            } else if (currentFrame.stderr != null && test.getPlayers().get(currentFrame.agentId).getAgentId() == -1) { // Regular frame
                logStringBuilder.append(logHeader);
                logStringBuilder.append(currentFrame.stderr);
                logStringBuilder.append(System.lineSeparator());
            }
        }

        // If nothing has been logged, we avoid creating an empty file
        if (logStringBuilder.length() > 0) {
            // Creates folder and file
            try {
                Path pathToFile = Paths.get(fileName);
                Files.createDirectories(pathToFile.getParent());
                Files.createFile(pathToFile);
            } catch (IOException ex) {
                LOG.error("Unable to create log file for " + response.success.gameId, ex);
            }

            // Writes content to file
            try (FileWriter fw = new FileWriter(fileName)) {
                fw.write(logStringBuilder.toString());
                fw.flush();
            } catch (IOException ex) {
                LOG.error("Unable to write log file for " + response.success.gameId, ex);
            }
        }
    }

    // DUMMY for test purpose
//    private TestOutput testCode(CGPlayApi cgPlayApi, TestInput test) {
//        PlayResponse resp = new PlayResponse();
//        resp.success = resp.new PlayResponseSuccess();
//        resp.success.frames = new ArrayList<>();
//        resp.success.scores = new ArrayList<>();
//        for (int i = 0; i < test.getPlayers().size(); i++) {
//            resp.success.scores.add((int) (Math.random() * 10));
//        }
//        if (saveLogs) dumpLogForPlay(resp);
//        return new TestOutput(test, resp);
//    }

    public void setResultWrapper(ResultWrapper resultWrapper) {
        this.resultWrapper = resultWrapper;
    }
}
