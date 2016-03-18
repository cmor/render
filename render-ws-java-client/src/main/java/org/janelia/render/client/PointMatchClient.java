package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating SIFT point matches for one or more canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class PointMatchClient {

    private enum MatchGroupIdAlgorithm {

        /** Assign match group id based upon the z value of the first rendered tile. */
        FIRST_TILE_Z,

        /** Assign match group id based upon the match collection name. */
        COLLECTION
    }

    private enum MatchIdAlgorithm {

        /** Assign match id based upon the id of the first rendered tile. */
        FIRST_TILE_ID,

        /** Assign match id based upon the z value of the first rendered tile. */
        FIRST_TILE_Z,

        /** Assign match id based upon the derived canvas name (e.g. c_00001). */
        CANVAS_NAME
    }

    private enum RenderFileFormat {
        JPG,
        PNG,
        TIF
    }

    @SuppressWarnings("ALL")
    public static class Parameters extends MatchDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --collection parameters defined in MatchDataClientParameters

        @Parameter(names = "--fillWithNoise", description = "Fill each canvas image with noise before rendering to improve point match derivation", required = false, arity = 0)
        private boolean fillWithNoise = true;

        @Parameter(names = "--SIFTfdSize", description = "SIFT feature descriptor size: how many samples per row and column", required = false)
        private Integer fdSize = 4;

        @Parameter(names = "--SIFTminScale", description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double minScale = 0.5;

        @Parameter(names = "--SIFTmaxScale", description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale", required = false)
        private Double maxScale = 0.85;

        @Parameter(names = "--SIFTsteps", description = "SIFT steps per scale octave", required = false)
        private Integer steps = 3;

        @Parameter(names = "--matchRod", description = "Ratio of distances for matches", required = false)
        private Float matchRod = 0.92f;

        @Parameter(names = "--matchMaxEpsilon", description = "Minimal allowed transfer error for matches", required = false)
        private Float matchMaxEpsilon = 20.0f;

        @Parameter(names = "--matchMinInlierRatio", description = "Minimal ratio of inliers to candidates for matches", required = false)
        private Float matchMinInlierRatio = 0.0f;

        @Parameter(names = "--matchMinNumInliers", description = "Minimal absolute number of inliers for matches", required = false)
        private Integer matchMinNumInliers = 10;

        @Parameter(names = "--numberOfThreads", description = "Number of threads to use for processing", required = false)
        private int numberOfThreads = 1;

        @Parameter(names = "--matchStorageFile", description = "File to store matches (omit if macthes should be stored through web service)", required = false)
        private String matchStorageFile = null;

        @Parameter(names = "--matchGroupIdAlgorithm", description = "Algorithm for deriving match group ids", required = false)
        private MatchGroupIdAlgorithm matchGroupIdAlgorithm = MatchGroupIdAlgorithm.FIRST_TILE_Z;

        @Parameter(names = "--matchIdAlgorithm", description = "Algorithm for deriving match ids", required = false)
        private MatchIdAlgorithm matchIdAlgorithm = MatchIdAlgorithm.FIRST_TILE_ID;

        @Parameter(names = "--debugDirectory", description = "Directory to save rendered canvases for debugging (omit to keep rendered data in memory only)", required = false)
        private String debugDirectory = null;
        private File validatedDebugDirectory = null;

        @Parameter(names = "--renderFileFormat", description = "Format for saved canvases (only relevant if debugDirectory is specified)", required = false)
        private RenderFileFormat renderFileFormat = RenderFileFormat.JPG;

        @Parameter(description = "canvas_1_URL, canvas_2_URL, [canvas_p_URL, canvas_q_URL], ... (each URL pair identifies render parameters for canvas pairs)", required = true)
        private List<String> renderParameterUrls;

        /**
         * @param  matchId  derived match id for canvas.
         *
         * @return file for debug save of rendered canvas.
         */
        public File getCanvasFile(final String matchId) {
            File canvasFile = null;
            if (validatedDebugDirectory != null) {
                canvasFile = new File(validatedDebugDirectory,
                                      matchId + "." + renderFileFormat.toString().toLowerCase());
            }
            return canvasFile;
        }

        /**
         * @param  renderParameters  render parameters used to generate the current canvas.
         *
         * @return match group id derived using the {@link #matchGroupIdAlgorithm}.
         */
        public String getMatchGroupId(final RenderParameters renderParameters) {
            String matchGroupId = null;
            switch(matchGroupIdAlgorithm) {
                case FIRST_TILE_Z:
                    if (renderParameters.hasTileSpecs()) {
                        matchGroupId = getTileZId(renderParameters.getTileSpecs().get(0), collection);
                    }
                    break;
            }
            if (matchGroupId == null) {
                matchGroupId = collection;
            }
            return matchGroupId;
        }

        /**
         * @param  renderParameters  render parameters used to generate the current canvas.
         * @param  canvasName        index based name for the current canvas (e.g. c_00001).
         *
         * @return match id derived using the {@link #matchIdAlgorithm}.
         */
        public String getMatchId(RenderParameters renderParameters,
                                 final String canvasName) {
            String matchId = null;
            if (renderParameters.hasTileSpecs()) {
                switch (matchIdAlgorithm) {
                    case FIRST_TILE_ID:
                        matchId = renderParameters.getTileSpecs().get(0).getTileId();
                        break;
                    case FIRST_TILE_Z:
                        matchId = getTileZId(renderParameters.getTileSpecs().get(0), canvasName);
                        break;
                }
            }
            if (matchId == null) {
                matchId = canvasName;
            }
            return matchId;
        }

        private String getTileZId(final TileSpec tileSpec,
                                  final String defaultValue) {
            String zId = defaultValue;
            final Double z = tileSpec.getZ();
            if (z != null) {
                zId = String.valueOf(z);
                if (zId.indexOf('.') == -1) {
                    zId = zId + ".0";
                }
            }
            return zId;
        }

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, PointMatchClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final PointMatchClient client = new PointMatchClient(parameters);

                client.extractFeatures();

                final List<CanvasMatches> canvasMatchesList = client.deriveMatches();

                client.saveMatches(canvasMatchesList);
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final Map<String, CanvasData> canvasUrlToDataMap;
    private final RenderDataClient renderDataClient;

    public PointMatchClient(final Parameters clientParameters)
            throws IllegalArgumentException {

        if ((clientParameters.renderParameterUrls.size() % 2) != 0) {
            throw new IllegalArgumentException("odd number of canvas URLs specified, URLs must be paired");
        }

        this.parameters = clientParameters;

        if (clientParameters.debugDirectory != null) {
            try {
                clientParameters.validatedDebugDirectory = new File(clientParameters.debugDirectory).getCanonicalFile();
            } catch (final IOException e) {
                throw new IllegalArgumentException(
                        "invalid debugDirectory '" + clientParameters.debugDirectory + "' specified", e);
            }
        }

        this.canvasUrlToDataMap = new LinkedHashMap<>(clientParameters.renderParameterUrls.size() * 2);

        for (final String canvasUrlString : clientParameters.renderParameterUrls) {
            if (! this.canvasUrlToDataMap.containsKey(canvasUrlString)) {
                this.canvasUrlToDataMap.put(canvasUrlString, new CanvasData(canvasUrlString,
                                                                            canvasUrlToDataMap.size(),
                                                                            clientParameters));
            }
        }

        this.renderDataClient = clientParameters.getClient();
    }

    public Map<String, CanvasData> getCanvasUrlToDataMap() {
        return canvasUrlToDataMap;
    }

    /**
     * Extract features from distinct set of canvases.
     */
    public void extractFeatures() throws Exception {

        LOG.info("extractFeatures: entry, extracting from {} canvases", canvasUrlToDataMap.size());

        final List<CanvasFeatureExtractorThread> extractorList = new ArrayList<>(canvasUrlToDataMap.size());

        for (final String canvasUrl : canvasUrlToDataMap.keySet()) {
            extractorList.add(new CanvasFeatureExtractorThread(canvasUrlToDataMap.get(canvasUrl),
                                                               parameters));
        }

        if (parameters.numberOfThreads > 1) {

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                extractorThread.start();
            }

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                LOG.info("extractFeatures: waiting for {} to finish ...", extractorThread);
                extractorThread.join();
            }

        } else {

            for (final CanvasFeatureExtractorThread extractorThread : extractorList) {
                extractorThread.run();
            }

        }

        LOG.info("extractFeatures: exit");
    }

    /**
     * Derive point matches for each canvas pair and write results.
     */
    public List<CanvasMatches> deriveMatches() throws Exception {

        LOG.info("deriveMatches: entry, extracting from {} canvases", canvasUrlToDataMap.size());

        final List<CanvasFeatureMatcherThread> matcherList = new ArrayList<>(parameters.renderParameterUrls.size());

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(parameters.matchRod,
                                                                      parameters.matchMaxEpsilon,
                                                                      parameters.matchMinInlierRatio,
                                                                      parameters.matchMinNumInliers);

        String pUrlString;
        String qUrlString;
        for (int i = 1; i < parameters.renderParameterUrls.size(); i = i + 2) {
            pUrlString = parameters.renderParameterUrls.get(i - 1);
            qUrlString = parameters.renderParameterUrls.get(i);
            matcherList.add(new CanvasFeatureMatcherThread(canvasUrlToDataMap.get(pUrlString),
                                                           canvasUrlToDataMap.get(qUrlString),
                                                           matcher));
        }


        if (parameters.numberOfThreads > 1) {

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                matcherThread.start();
            }

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                LOG.info("extractFeatures: waiting for {} to finish ...", matcherThread);
                matcherThread.join();
            }

        } else {

            for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
                matcherThread.run();
            }

        }

        final List<CanvasMatches> canvasMatchesList = new ArrayList<>(matcherList.size());
        for (final CanvasFeatureMatcherThread matcherThread : matcherList) {
            canvasMatchesList.add(matcherThread.getMatches());
        }

        LOG.info("deriveMatches: exit");

        return canvasMatchesList;
    }

    public void saveMatches(final List<CanvasMatches> canvasMatchesList) throws Exception {

        LOG.info("saveMatches: entry, canvasMatchesList.size={}", canvasMatchesList.size());

        CanvasMatches canvasMatches;
        if (parameters.matchStorageFile != null) {

            final Path storagePath = Paths.get(parameters.matchStorageFile);

            try (BufferedWriter writer = Files.newBufferedWriter(storagePath)) {

                writer.write("[\n");

                for (int i = 0; i < canvasMatchesList.size(); i++) {
                    if (i > 0) {
                        writer.write(",\n");
                    }
                    canvasMatches = canvasMatchesList.get(i);
                    writer.write(canvasMatches.toJson());
                }

                writer.write("\n]\n");
            }


        } else {

            renderDataClient.saveMatches(canvasMatchesList);

        }

        LOG.info("saveMatches: exit");
    }

    /**
     * Helper class to hold data (render parameters, features, etc.) for each canvas.
     */
    public static class CanvasData {

        private final RenderParameters renderParameters;
        private final String matchGroupId;
        private final String matchId;
        private List<Feature> featureList;

        public CanvasData(final String canvasUrl,
                          final int canvasIndex,
                          final Parameters clientParameters) {

            this.renderParameters = RenderParameters.loadFromUrl(canvasUrl);
            this.matchGroupId = clientParameters.getMatchGroupId(this.renderParameters);
            final String canvasName = "c_" + String.format("%05d", canvasIndex);
            this.matchId = clientParameters.getMatchId(this.renderParameters, canvasName);
            this.featureList = null;
        }

        public void setFeatureList(final List<Feature> featureList) {
            this.featureList = featureList;
        }

        public String getMatchGroupId() {
            return matchGroupId;
        }

        public String getMatchId() {
            return matchId;
        }

        public int getNumberOfFeatures() {
            return (featureList == null) ? 0 : featureList.size();
        }

        @Override
        public String toString() {
            return matchGroupId + "__" + matchId;
        }
    }

    /**
     * Thread wrapper that allows feature extraction to be done in parallel.
     */
    private static class CanvasFeatureExtractorThread extends Thread {

        private final CanvasData canvasData;
        private final File renderFile;
        private final CanvasFeatureExtractor extractor;

        public CanvasFeatureExtractorThread(final CanvasData canvasData,
                                            final Parameters clientParameters) {

            this.canvasData = canvasData;
            this.renderFile = clientParameters.getCanvasFile(canvasData.matchId);

            final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
            siftParameters.fdSize = clientParameters.fdSize;
            siftParameters.steps = clientParameters.steps;

            this.extractor = new CanvasFeatureExtractor(siftParameters,
                                                        clientParameters.minScale,
                                                        clientParameters.maxScale,
                                                        clientParameters.fillWithNoise);
        }

        @Override
        public void run() {
            canvasData.setFeatureList(
                    extractor.extractFeatures(canvasData.renderParameters,
                                              renderFile));
        }

        @Override
        public String toString() {
            return "CanvasFeatureExtractorThread{" + canvasData.matchId +'}';
        }
    }

    /**
     * Thread wrapper that allows match derivation to be done in parallel.
     */
    private static class CanvasFeatureMatcherThread extends Thread {

        private final CanvasData pCanvasData;
        private final CanvasData qCanvasData;

        private final CanvasFeatureMatcher matcher;

        private CanvasFeatureMatchResult matchResult;

        public CanvasFeatureMatcherThread(final CanvasData pCanvasData,
                                          final CanvasData qCanvasData,
                                          final CanvasFeatureMatcher matcher) {
            this.pCanvasData = pCanvasData;
            this.qCanvasData = qCanvasData;
            this.matcher = matcher;
        }

        @Override
        public void run() {
            matchResult = matcher.deriveMatchResult(pCanvasData.featureList, qCanvasData.featureList);
        }

        public CanvasMatches getMatches() {
            return new CanvasMatches(pCanvasData.matchGroupId,
                                     pCanvasData.matchId,
                                     qCanvasData.matchGroupId,
                                     qCanvasData.matchId,
                                     matchResult.getInlierMatches());
        }

        @Override
        public String toString() {
            return "CanvasFeatureMatcherThread{" + pCanvasData.matchId + "__" + qCanvasData.matchId + '}';
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchClient.class);
}