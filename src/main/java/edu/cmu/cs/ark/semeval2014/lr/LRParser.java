package edu.cmu.cs.ark.semeval2014.lr;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import edu.cmu.cs.ark.semeval2014.ParallelParser;
import edu.cmu.cs.ark.semeval2014.common.InputAnnotatedSentence;
import edu.cmu.cs.ark.semeval2014.lr.fe.*;
import edu.cmu.cs.ark.semeval2014.utils.Corpus;
import sdp.graph.Edge;
import sdp.graph.Graph;
import sdp.io.GraphReader;
import util.U;
import util.Vocabulary;
import util.misc.Pair;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static edu.cmu.cs.ark.semeval2014.lr.fe.BasicLabelFeatures.*;

public class LRParser {
	public static final String NO_EDGE = "NOEDGE";
	private static final String BIAS_NAME = "***BIAS***";

	// 1. Data structures
	static InputAnnotatedSentence[] inputSentences = null; // full dataset
	static List<int[][]> graphMatrices = null;  // full dataset

	static List<FE.LabelFE> labelFeatureExtractors = new ArrayList<>();

	static Model model;
	static float[] ssGrad;  // adagrad history info. parallel to coefs[].

	@Parameter(names="-learningRate")
	static double learningRate = .1;
	
	// 3. Model parameter-ish options
	static int maxEdgeDistance = 10;
	@Parameter(names="-l2reg")
	static double l2reg = 1;
	@Parameter(names="-noedgeWeight")
	static double noedgeWeight = 0.2;
	
	// 4. Runtime options
	@Parameter(names="-verboseFeatures")
	static boolean verboseFeatures = false;
	@Parameter(names="-useFeatureCache", arity=1)
    static boolean useFeatureCache = true;
    @Parameter(names="-saveEvery")
    static int saveEvery = 10;  // -1 to disable intermediate model saves
    @Parameter(names="-numIters")
	static int numIters = 30;

	// label feature flags
	@Parameter(names = "-useDmLabelFeatures")
	static boolean useDmLabelFeatures = false;
	@Parameter(names = "-usePasLabelFeatures")
	static boolean usePasLabelFeatures = false;
	@Parameter(names = "-usePcedtLabelFeatures")
	static boolean usePcedtLabelFeatures = false;

	@Parameter(names="-mode", required=true)
	static String mode;
    @Parameter(names="-model",required=true)
	static String modelFile;
    @Parameter(names={"-sdpInput","-sdpOutput"}, required=true)
	static String sdpFile;
    @Parameter(names="-depInput", required=true)
	static String depFile;

	public static void main(String[] args) throws IOException {
		new JCommander(new LRParser(), args);  // seems to write to the static members.

		assert mode.equals("train") || mode.equals("test");

		// Data loading
		inputSentences = Corpus.getInputAnnotatedSentences(depFile);
		U.pf("%d input sentences\n", inputSentences.length);

		if (mode.equals("train")) {
			trainModel();
		} else if (mode.equals("test")) {
			model = Model.load(modelFile);
			U.pf("Writing predictions to %s\n", sdpFile);
			double t0, dur;
			t0 = System.currentTimeMillis();
			ParallelParser.makePredictions(model, inputSentences, sdpFile);
			dur = System.currentTimeMillis() - t0;
			U.pf("\nPRED TIME %.1f sec, %.1f ms/sent\n", dur/1e3, dur/inputSentences.length);
		}
	}

	private static Model trainModel() throws IOException {
		double t0;
		double dur;
		U.pf("Reading graphs from %s\n", sdpFile);
		final List<Graph> graphs = readGraphs(sdpFile);

		// build up the edge label vocabulary
		Vocabulary labelVocab = new Vocabulary();
		labelVocab.num(NO_EDGE);
		for (Graph graph : graphs) {
			for (Edge e : graph.getEdges()) {
				labelVocab.num(e.label);
			}
		}
		labelVocab.lock();

		// build up label feature vocab
		initializeLabelFeatureExtractors();
		final Pair<Vocabulary, List<int[]>> vocabAndFeatsByLabel =
				extractAllLabelFeatures(labelVocab, labelFeatureExtractors);
		final Vocabulary labelFeatureVocab = vocabAndFeatsByLabel.first;
		final List<int[]> featuresByLabel = vocabAndFeatsByLabel.second;

		assert graphs.size() == inputSentences.length;

		// convert graphs to adjacency matrices
		graphMatrices = new ArrayList<>();
		for (int snum=0; snum<graphs.size(); snum++) {
			final InputAnnotatedSentence sent = inputSentences[snum];
			final Graph graph = graphs.get(snum);
			assert sent.sentenceId().equals(graph.id.replace("#",""));
			graphMatrices.add(convertGraphToAdjacencyMatrix(graph, sent.size(), labelVocab));
		}

		final Vocabulary perceptVocab = new Vocabulary();
		perceptVocab.num(BIAS_NAME);
		model = new Model(labelVocab, labelFeatureVocab, featuresByLabel, perceptVocab);

		t0 = System.currentTimeMillis();
		trainingOuterLoopOnline(model, modelFile);
		dur = System.currentTimeMillis() - t0;
		U.pf("TRAINLOOP TIME %.1f sec\n", dur/1e3);

		model.save(modelFile);
		if (useFeatureCache)
			Files.delete(Paths.get(featureCacheFile));
		return model;
	}

	private static int[][] convertGraphToAdjacencyMatrix(Graph graph, int n, Vocabulary labelVocab) {
		int[][] edgeMatrix = new int[n][n];
		for (int[] row : edgeMatrix) {
			Arrays.fill(row, labelVocab.num(NO_EDGE));
		}
		for (Edge e : graph.getEdges()) {
			edgeMatrix[e.source-1][e.target-1] = labelVocab.num(e.label);
		}
		return edgeMatrix;
	}

	private static Pair<Vocabulary, List<int[]>> extractAllLabelFeatures(
			Vocabulary labelVocab,
			List<FE.LabelFE> labelFeatureExtractors)
	{
		final Vocabulary labelFeatVocab = new Vocabulary();
		final List<int[]> featsByLabel = new ArrayList<>(labelVocab.size());
		for (int labelIdx = 0; labelIdx < labelVocab.size(); labelIdx++) {
			final InMemoryNumberizedFeatureAdder adder = new InMemoryNumberizedFeatureAdder(labelFeatVocab);
			for (FE.LabelFE fe : labelFeatureExtractors) {
				fe.features(labelVocab.name(labelIdx), adder);
			}
			featsByLabel.add(adder.features());
		}
		labelFeatVocab.lock();
		return Pair.makePair(labelFeatVocab, featsByLabel);
	}

	private static List<Graph> readGraphs(String sdpFile) throws IOException {
		final ArrayList<Graph> graphs = new ArrayList<>();
		try (GraphReader reader = new GraphReader(sdpFile)) {
			Graph graph;
			while ((graph = reader.readGraph()) != null) {
				graphs.add(graph);
			}
		}
		return graphs;
	}

	public static boolean badDistance(int i, int j) {
		return i==j || Math.abs(i-j) > maxEdgeDistance;
	}
	
	static long totalPairs = 0;  // only for diagnosis
	
	static class TokenFeatAdder extends FE.FeatureAdder {
		int i=-1;
		NumberizedSentence ns;
		InputAnnotatedSentence is; // only for debugging
		final Vocabulary perceptVocab;

		TokenFeatAdder(Vocabulary perceptVocab) {
			this.perceptVocab = perceptVocab;
		}

		@Override
		public void add(String featname, double value) {
			if (verboseFeatures) {
				U.pf("NODEFEAT\t%s:%d\t%s\n", is.sentence()[i], i, featname);
			}

			// this is kinda a hack, put it in both directions for every edge.
			// we could use smarter data structures rather than the full matrix
			// of edge featvecs to represent this more compactly.

			String ff;
			int featnum;
			
			ff = U.sf("%s::ashead", featname);
			featnum = perceptVocab.num(ff);
			if (featnum!=-1) {
				for (int j=0; j<ns.T; j++) {
					if (badDistance(i,j)) continue;
					ns.add(i,j, featnum, value);
				}
			}
			
			ff = U.sf("%s::aschild", featname);
			featnum = perceptVocab.num(ff);
			if (featnum!=-1) {
				for (int j=0; j<ns.T; j++) {
					if (badDistance(j,i)) continue;
					ns.add(j,i, featnum, value);
				}
			}
		}
	}

	static class EdgeFeatAdder extends FE.FeatureAdder {
		int i=-1, j=-1;
		NumberizedSentence ns;
		// these are only for debugging
		InputAnnotatedSentence is;
		int[][] goldEdgeMatrix;
		final Vocabulary perceptVocab;

		EdgeFeatAdder(Vocabulary perceptVocab) {
			this.perceptVocab = perceptVocab;
		}

		@Override
		public void add(String featname, double value) {
			int perceptnum = perceptVocab.num(featname);
			if (perceptnum==-1) return;
			
			ns.add(i,j, perceptnum, value);
			
			if (verboseFeatures) {
				U.pf("WORDS %s:%d -> %s:%d\tGOLD %s\tEDGEFEAT %s %s\n", is.sentence()[i], i, is.sentence()[j], j,
						goldEdgeMatrix!=null ? model.labelVocab.name(goldEdgeMatrix[i][j]) : null, featname, value);
			}

		}
	}

	/**
	 * goldEdgeMatrix is only for feature extractor debugging verbose reports 
	 */
	public static NumberizedSentence extractFeatures(Model model, InputAnnotatedSentence is, int[][] goldEdgeMatrix) {
		final int biasIdx = model.perceptVocab.num(BIAS_NAME);

		NumberizedSentence ns = new NumberizedSentence( is.size() );
		TokenFeatAdder tokenAdder = new TokenFeatAdder(model.perceptVocab);
		EdgeFeatAdder edgeAdder = new EdgeFeatAdder(model.perceptVocab);
		tokenAdder.ns=edgeAdder.ns=ns;
		
		// only for verbose feature extraction reporting
		tokenAdder.is = edgeAdder.is=is;
		edgeAdder.goldEdgeMatrix = goldEdgeMatrix;

		final List<FE.FeatureExtractor> featureExtractors = initializeFeatureExtractors();
		for (FE.FeatureExtractor fe : featureExtractors) {
			assert (fe instanceof FE.TokenFE) || (fe instanceof FE.EdgeFE) : "all feature extractors need to implement one of the interfaces!";
			fe.initializeAtStartup();
			fe.setupSentence(is);
		}
		
		for (edgeAdder.i=0; edgeAdder.i<ns.T; edgeAdder.i++) {
			
			tokenAdder.i = edgeAdder.i;
			for (FE.FeatureExtractor fe : featureExtractors) {
				if (fe instanceof FE.TokenFE) {
					((FE.TokenFE) fe).features(tokenAdder.i, tokenAdder);
				}
			}
			for (edgeAdder.j=0; edgeAdder.j<ns.T; edgeAdder.j++) {
				if (badDistance(edgeAdder.i,edgeAdder.j)) continue;
				
				// bias term
				ns.add(edgeAdder.i, edgeAdder.j, biasIdx, 1.0);
				
				// edge features
				for (FE.FeatureExtractor fe : featureExtractors) {
					if (fe instanceof FE.EdgeFE) {
						((FE.EdgeFE) fe).features(edgeAdder.i, edgeAdder.j, edgeAdder);
					}
				}
			}
		}
		return ns;
	}
	
	static NumberizedSentence extractFeatures(Model model, int snum) {
		return extractFeatures(model, inputSentences[snum], graphMatrices !=null ? graphMatrices.get(snum) : null);
	}

    static void trainingOuterLoopOnline(Model model, String modelFilePrefix) throws IOException {
    	for (int outer=0; outer<numIters; outer++) {
    		U.pf("iter %3d ", outer);  System.out.flush();
    		double t0 = System.currentTimeMillis();
    		
    		if (outer==0) {
    			cacheReadMode = false;
    			openCacheForWriting();
    		} else {
    			cacheReadMode = true;
    			resetCacheReader();
    		}
    		
    		trainOnlineIter(model, outer==0);
    		
        	double dur = System.currentTimeMillis() - t0;
        	U.pf("%.1f sec, %.1f ms/sent\n", dur/1000, dur/inputSentences.length);
    		
        	if (saveEvery >= 0 && outer % saveEvery == 0)
        		model.save(U.sf("%s.iter%s", modelFilePrefix, outer));
    		
    		if (outer==0) {
    			closeCacheAfterWriting();
    		}
    		
    		if (outer==0) U.pf("%d percepts, %d nnz\n", model.perceptVocab.size(), NumberizedSentence.totalNNZ);
    	}
    }

    static void growCoefsIfNecessary() {
    	if (ssGrad==null) {
    		int n = Math.min(10000, model.perceptVocab.size());
    		model.coefs = new float[n*model.labelFeatureVocab.size()];
    		ssGrad = new float[n*model.labelFeatureVocab.size()];
    	}
    	else if (model.labelFeatureVocab.size()*model.perceptVocab.size() > model.coefs.length) {
    		int newLen = (int) Math.ceil(1.2 * model.perceptVocab.size()) * model.labelFeatureVocab.size();
			model.coefs = NumberizedSentence.growToLength(model.coefs, newLen);
            ssGrad = NumberizedSentence.growToLength(ssGrad, newLen);
            assert model.coefs.length==ssGrad.length;
        }
    }
	
    /** From the new gradient value, update this feature's learning rate and return it. */
    static double adagradStoreRate(int featnum, double g) {
        ssGrad[featnum] += g*g;
        if (ssGrad[featnum] < 1e-2) return 10.0; // 1/sqrt(.01)
        return 1.0 / Math.sqrt(ssGrad[featnum]);
    }
    
    
    /** adagrad: http://www.ark.cs.cmu.edu/cdyer/adagrad.pdf */ 
    static void trainOnlineIter(Model model, boolean firstIter) throws FileNotFoundException {
		assert model.labelVocab.isLocked() : "since we have autolabelconj, can't tolerate label vocab expanding during a training pass.";
		assert model.labelFeatureVocab.isLocked() : "since we have autolabelconj, can't tolerate label vocab expanding during a training pass.";

		double ll = 0;
        for (int snum=0; snum<inputSentences.length; snum++) {
        	U.pf(".");
            
            NumberizedSentence ns = getNextExample(snum);
            if (firstIter) {
                growCoefsIfNecessary();
            }
    		int[][] edgeMatrix = graphMatrices.get(snum);
            ll += updateExampleLogReg(ns, edgeMatrix);
            
            if (firstIter && snum>0 && snum % 1000 == 0) {
            	U.pf("%d sents, %.3fm percepts, %.3fm finefeats allocated, %.1f MB mem used\n", 
            			snum+1, model.perceptVocab.size()/1e6, model.coefs.length/1e6,
            			Runtime.getRuntime().totalMemory()/1e6
            			);
            }
        }
        //  logprior  =  - (1/2) lambda || beta ||^2
        //  gradient =  - lambda beta
        for (int f=0; f< model.coefs.length; f++) {
            ll -= 0.5 * l2reg * model.coefs[f]*model.coefs[f];
            double g = l2reg * model.coefs[f];
			model.coefs[f] -= adagradStoreRate(f,g) * learningRate * g;
        }
        U.pf("ll %.1f  ", ll);
    }

	static double updateExampleLogReg(NumberizedSentence sentence, int[][] edgeMatrix) {
		final int noEdgeIdx = model.labelVocab.num(NO_EDGE);
		double ll = 0;

		double[][][] probs = model.inferEdgeProbs(sentence);
		
		for (int kk = 0; kk < sentence.nnz; kk++) {
		    int i = sentence.i(kk);
			int j = sentence.j(kk);
			int perceptNum = sentence.perceptnum(kk);
			final int goldLabelIdx = edgeMatrix[i][j];
			// manually downweight the NO_EDGE label
			final double w = goldLabelIdx == noEdgeIdx ? noedgeWeight : 1.0;

		    for (int label = 0; label < model.labelVocab.size(); label++) {
				int isObserved = goldLabelIdx == label ? 1 : 0;
				double resid = isObserved - probs[i][j][label];
				double g = w * resid * sentence.value(kk);
				for (int labelFeatureIdx : model.featuresByLabel.get(label)) {
					int ffnum = model.coefIdx(perceptNum, labelFeatureIdx);
					double rate = adagradStoreRate(ffnum, g);
					model.coefs[ffnum] += learningRate * rate * g;
				}
		    }
		}
		
		// loglik is completely unnecessary for optimization, just nice for diagnosis.
		for (int i=0;i<sentence.T;i++) {
		    for (int j=0; j<sentence.T;j++) {
		        if (badDistance(i,j)) continue;
		        double w = edgeMatrix[i][j]==0 ? noedgeWeight : 1.0;
		        ll += w * Math.log(probs[i][j][edgeMatrix[i][j]]);
		    }
		}
		return ll;
	}

	// START feature cache stuff
    // uses https://github.com/EsotericSoftware/kryo found from http://stackoverflow.com/questions/239280/which-is-the-best-alternative-for-java-serialization
    
    static Kryo kryo;
    static { kryo = new Kryo(); }
    static boolean cacheReadMode = false;
    static Input kryoInput;
    static Output kryoOutput;
    static String featureCacheFile;
    static { featureCacheFile = "featcache." + MiscUtil.getProcessId("bla") + ".bin"; }
    
    /** this should work with or without caching enabled.
     * for caching, assume accesses are in order!!
     */
    static NumberizedSentence getNextExample(int snum) {
    	if (useFeatureCache && cacheReadMode) {
    		return kryo.readObject(kryoInput, NumberizedSentence.class);
    	} else {
    		NumberizedSentence ns = extractFeatures(model, snum);
    		if (useFeatureCache) { 
    			kryo.writeObject(kryoOutput, ns);
    		}
    		return ns;
    	}
    }
    static void openCacheForWriting() throws FileNotFoundException {
    	if (!useFeatureCache) return;
        kryoOutput = new Output(new FileOutputStream(featureCacheFile));
    }
    static void closeCacheAfterWriting() {
    	if (!useFeatureCache) return;
    	kryoOutput.close();
    	long size = new File(featureCacheFile).length();
    	U.pf("Feature cache (%s) is %.1f MB, %.2f MB/sent\n", 
    			featureCacheFile, size*1.0/1e6, size*1.0/1e6/inputSentences.length);
    }
    static void resetCacheReader() throws FileNotFoundException {
    	if (!useFeatureCache) return;
    	if (kryoInput != null) {
        	kryoInput.close();
    	}
    	kryoInput = new Input(new FileInputStream(featureCacheFile));
    }
    
    // END feature cache stuff
    

	///////////////////////////////////////////////////////////
	
	static List<FE.FeatureExtractor> initializeFeatureExtractors() {
		final List<FE.FeatureExtractor> allFE = new ArrayList<>();
		allFE.add(new BasicFeatures());
		allFE.add(new LinearOrderFeatures());
		allFE.add(new DependencyPathv1());
		allFE.add(new SubcatSequenceFE());
		return allFE;
	}

	static void initializeLabelFeatureExtractors() {
		// always use the name of the label itself
		labelFeatureExtractors.add(new PassThroughFe());
		if (useDmLabelFeatures) {
			labelFeatureExtractors.add(new DmFe());
		}
		if (usePasLabelFeatures) {
			labelFeatureExtractors.add(new PasFe());
		}
		if (usePcedtLabelFeatures) {
			labelFeatureExtractors.add(new PcedtFE());
		}
	}
}
