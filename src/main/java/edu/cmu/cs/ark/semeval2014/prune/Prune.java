package edu.cmu.cs.ark.semeval2014.prune;

import java.util.*;

import util.U;
import util.Vocabulary;


import edu.cmu.cs.ark.semeval2014.common.InputAnnotatedSentence;
import edu.cmu.cs.ark.semeval2014.lr.LRParser;
import edu.cmu.cs.ark.semeval2014.lr.fe.FE;
import edu.cmu.cs.ark.semeval2014.lr.fe.BasicLabelFeatures.PassThroughFe;

public class Prune {
	private int numIter = 1;
	private List<int[]> trainingSingletonIndicators;
	private List<int[]> trainingPredicateIndicators;
	private PruneModel singletonModel;
	private PruneModel predicateModel;
	private InputAnnotatedSentence[] inputSentences;
	private List<FE.FeatureExtractor> allFE = new ArrayList<>();
	
	// model parameters
	private Vocabulary labelVocab;
	
	private List<FE.LabelFE> labelFeatureExtractors = new ArrayList<>();
	private final String TRUE = "t";
	private final String FALSE = "f";
	private final String modelFileName;
	private final String singletonFileName = "singletonModel.ser";
	private final String predicateFileName = "predicateModel.ser";
		
	// featuresByLabel: map from the labels to the features computed from the labels
	// labelFeatureVocab maps from the features computed from the labels to a number representing that feature
	
	public Prune(InputAnnotatedSentence[] inputSentences, String modelFileName){
		this.inputSentences = inputSentences;
		this.modelFileName = modelFileName;

		labelVocab = new Vocabulary();
		labelVocab.num(FALSE);
		labelVocab.num(TRUE);
	}
	
	/*
	// TODO: This is incomplete
	private List<int[]> convertGraphToBinaryTops(List<int[][]> graphs, Vocabulary graphLabelVocab){
		List<int[]> tops = new ArrayList<int[]>();
		int counter = 0; 
		for (int[][] g : graphs){
			printSentenceAndGraph(inputSentences[counter],g);
			int[] ts = new int[g.length];
			for (int i = 0; i < ts.length; i++){
				ts[i] = 0;
			}
			for (int i = 0; i < g.length; i++){
				for (int j = 0 ; j < g.length; j++){
					//TODO: something here to convert from the graphs to a list of tops
				}
			}
			counter++;
			if (counter == 2)
				System.exit(1);
		}
		return tops;
	}
	*/
	
	// to find if a token is a singleton, the ith row and ith column should be entirely no-edges
	// returns: a list of int arrays. Each array correspnds to a sentence, and each element of a given array corresponds to a word. If an 
	// 		element of the array is 1, the associated token is a singleton.
	private List<int[]> convertGraphsToSingletonIndicators(List<int[][]> graphs, Vocabulary graphLabelVocab){
		List<int[]> singletons = new ArrayList<>();
		for (int[][] g : graphs){
			int[] singles = new int[g.length];
			// to instantiate the array of singletons
			for (int i = 0; i < singles.length; i++){
				singles[i] = labelVocab.num(TRUE);
			}
			for (int i = 0; i < g.length; i++){
				for (int j = 0; j < g.length; j++){
					if (g[i][j] != graphLabelVocab.num(LRParser.NO_EDGE)){
						singles[i] = labelVocab.num(FALSE);
						singles[j] = labelVocab.num(FALSE);
					}
				}
			}
			singletons.add(singles);
		}
		return singletons;
	}
	
	
	// Args: takes a list of graphs and a Vocabulary that maps from labels -> #s (the #s are the way labels are represented in the graph
	// To find if a token is a predicate, the ith row should be entirely no-edges.
	// returns: a list of int arrays. Each array correspnds to a sentence, and each element of a given array corresponds to a word. If an 
	// 		element of the array is 1, the associated token is a predicate.
	// Note: predicates are tokens that have a child -- they are NOT singletons and NOT leafnodes.
	private List<int[]> convertGraphsToPredicateIndicators(List<int[][]> graphs, Vocabulary graphLabelVocab){
		List<int[]> predicates = new ArrayList<>();
		for (int[][] g : graphs){
			int[] preds = new int[g.length];
			// to instantiate the array of singletons
			for (int i = 0; i < preds.length; i++){
				preds[i] = labelVocab.num(FALSE);
			}
			for (int i = 0; i < g.length; i++){
				for (int j = 0; j < g.length; j++){
					if (g[i][j] != graphLabelVocab.num(LRParser.NO_EDGE)){
						preds[i] = labelVocab.num(TRUE);
					}
				}
			}
			predicates.add(preds);
		}
		return predicates;
	}
	
	void initializeLabelFeatureExtractors() {
		// this is the identity feature. This allows us to simply get the label itself as it's feature
		labelFeatureExtractors.add(new PassThroughFe());
	}
	
	// to learn the weight vectors 
	public void trainModels(Vocabulary lv, List<int[][]> graphMatrices){
		initialize(graphMatrices, lv);
		
		// to learn the weights for the singletons
		singletonModel = new PruneModel();
		initializeWeights(singletonModel);
		trainingOuterLoopOnline(singletonModel, trainingSingletonIndicators);
		singletonModel.save(modelFileName + "." + singletonFileName);
		
		//trainError(sModel.weights, singletons);
		
		// to learn the weights for the predicates
		predicateModel = new PruneModel();
		initializeWeights(predicateModel);
		trainingOuterLoopOnline(predicateModel, trainingPredicateIndicators);
		predicateModel.save(modelFileName + "." + predicateFileName);
		
		//trainError(pModel.weights, predicates);
//		dumpDecisions(10);
		
	}
	
	public void dumpDecisions(int snum) {
		InputAnnotatedSentence sent = inputSentences[snum];
		U.pf("\nSENTENCE %s\n", sent.sentenceId());
		for (int t=0; t<inputSentences[snum].size(); t++) {
			U.pf("issg(g,p) = %d,%d  ispred(g,p) = %d,%d  ||| %s\n", 
					trainingSingletonIndicators.get(snum)[t], sent.singletons()[t], 
					trainingPredicateIndicators.get(snum)[t], sent.predicates()[t], 
					sent.sentence()[t]);
		}
	}

	private void trainError(Map<String, Double> weights,
			List<int[]> test) {
		int correct = 0;
		int incorrect = 0;
        for (int snum=0; snum<inputSentences.length; snum++) {
        	U.pf(".");
        	List<Map<String, Set<String>>> feats = ghettoFeats(snum);
    		int[] sequenceOfLabels = test.get(snum);
    		Viterbi v = new Viterbi(weights);
    		String[] labels = v.decode(feats);
    		for (int i = 0; i < sequenceOfLabels.length; i++){
    			if (Integer.parseInt(labels[i+1]) == sequenceOfLabels[i])
    				correct++;
    			else
    				incorrect++;
    		}
        }
        System.out.println("The number of correct labels: " + correct);
        System.out.println("The number of incorrect labels: " + incorrect);
        System.out.println("The totaly number of labels: " + (correct + incorrect));
        System.out.println("Correct / (correct + incorrect): " + correct * 1.0 / (incorrect + correct));
        System.out.println();
	}

	private void initialize(List<int[][]> graphMatrices, Vocabulary lv) {
		trainingSingletonIndicators = convertGraphsToSingletonIndicators(graphMatrices, lv);
		trainingPredicateIndicators = convertGraphsToPredicateIndicators(graphMatrices, lv);
	}

	private void printUniqueWeights(Map<String, Double> weights){
        for (String w : weights.keySet()){
        	if (weights.get(w) != 0.0)
        		System.out.println(w + ": " + weights.get(w));
        }
	}
	
	private void initializeWeights(PruneModel model){
		for (int i = 0; i < inputSentences.length; i++){
			List<Map<String, Set<String>>> feats = ghettoFeats(i);
			for (int j = 0; j < feats.size(); j++){
				for (String l : feats.get(j).keySet()){
					for (String w : feats.get(j).get(l)){
						model.weights.put(w, 0.0);						
						//weights.put(w + "_" + l, 0.0);
						
					}
				}
			}
		}
		// to initialize the transitions
		for (String prev : labelVocab.names()){
			for (String cur : labelVocab.names()){
				model.weights.put(labelVocab.num(prev) + "_" + labelVocab.num(cur), 0.0);
			}
		}
	}

	// the outer training loop. loops over the data numIter times.
	private void trainingOuterLoopOnline(PruneModel singletonModel, List<int[]> train) {
		for (int i = 0; i < numIter; i++){
			trainOnlineIter(singletonModel, train);
		}
	}

	// the inner training loop. Within the dataset, loops over each example.
	private void trainOnlineIter(PruneModel model, List<int[]> train ) {
        for (int snum=0; snum<inputSentences.length; snum++) {
        	U.pf(".");
        	List<Map<String, Set<String>>> feats = ghettoFeats(snum);
    		int[] sequenceOfLabels = train.get(snum);
    		ghettoPerceptronUpdate(sequenceOfLabels, feats, model);
        }
	}
	
	private void ghettoPerceptronUpdate(int[] sequenceOfLabels, List<Map<String, Set<String>>> feats, PruneModel singletonModel ){
		runViterbi(feats, singletonModel, sequenceOfLabels);
	}
	
	private void runViterbi(List<Map<String, Set<String>>> feats, PruneModel model, int[] gold ) {
		Viterbi v = new Viterbi(model.weights);
		String[] labels = v.decode(feats);

		// downweighting the predicted transition weights
		for (int i = 1; i < labels.length - 1; i++){
			model.weights.put(labels[i] + "_" + labels[i+1], model.weights.get(labels[i] + "_" + labels[i+1]) -1 );
		}
		
		// downweighting the predicted emission weights
		for (int i = 1; i < feats.size() - 1; i++){
			for (String f : feats.get(i).get(labels[i])){
				model.weights.put(f, model.weights.get(f) - 1);
			}
		}
		
		// upweighting the gold transition weightns
		for (int i = 0; i < gold.length-1; i++){
			String ith = Integer.toString(gold[i]);
			String ithPlusOne = Integer.toString(gold[i+1]);
			model.weights.put(ith + "_" + ithPlusOne, model.weights.get(ith + "_" + ithPlusOne) +1 );
		}
		
		// upweighting the gold emission weights
		for (int i = 1; i < feats.size() - 1; i++){
			String g = Integer.toString(gold[i - 1]);
			for (String f : feats.get(i).get(g)){
				model.weights.put(f, model.weights.get(f) + 1);
			}
		}
	}


	private List<Map<String, Set<String>>> ghettoFeats(int snum){
		
		List<Map<String, Set<String>>> feats = new ArrayList<Map<String, Set<String>>>();
		
		Map<String, Set<String>> start = new HashMap<String, Set<String>>();
		start.put("<START>", new HashSet<String>());
		feats.add(start);

		for (int i = 0; i < inputSentences[snum].sentence().length; i++){
			// adding the token itself as a feature
			Set<String> wordFeats = new HashSet<String>();
			wordFeats.add("token=" + inputSentences[snum].sentence()[i]);
			
			Set<String> posFeats = new HashSet<String>();
			posFeats.add("pos=" + inputSentences[snum].pos()[i]);
			
			
			Map<String, Set<String>> featsByLabel = initializeFeats();
			makeFeatsByLabel(wordFeats, featsByLabel);
			makeFeatsByLabel(posFeats, featsByLabel);
			
			feats.add(featsByLabel);
		}
		Map<String, Set<String>> stop = new HashMap<String, Set<String>>();
		stop.put("<STOP>", new HashSet<String>());
		feats.add(stop);
		
		return feats;
	}
	
	private Map<String, Set<String>> initializeFeats(){
		Map<String, Set<String>> featsByLabel = new HashMap<String, Set<String>>();
		Set<String> conjoinedTrue = new HashSet<String>();
		Set<String> conjoinedFalse = new HashSet<String>();
		featsByLabel.put(Integer.toString(labelVocab.num(TRUE)), conjoinedTrue);
		featsByLabel.put(Integer.toString(labelVocab.num(FALSE)), conjoinedFalse);
		return featsByLabel;
	}
	
	private void makeFeatsByLabel(Set<String> wordFeats, Map<String, Set<String>> featsByLabel){
		for (String s : wordFeats){
			featsByLabel.get(Integer.toString(labelVocab.num(TRUE))).add(s + "_" + labelVocab.num(TRUE));
			featsByLabel.get(Integer.toString(labelVocab.num(FALSE))).add(s + "_" + labelVocab.num(FALSE));
		}
	}
	
	private void printSentenceAndGraph(InputAnnotatedSentence inputSentences, int[][] g){
		for (int i = 0; i < inputSentences.sentence().length; i++){
			System.out.print(i + ":" + inputSentences.sentence()[i] + " ");
		}
		System.out.println("\n");
		System.out.print("   ");
		for (int i = 0; i < g.length; i++){
			if (i < 10)
				System.out.print(i + "  ");
			else
				System.out.print(i + " ");
		}
		System.out.println();
		for (int i = 0; i < g.length; i++){
			if (i < 10)
				System.out.print(i + "  ");
			else
				System.out.print(i + " ");
			for (int j = 0; j < g.length; j++){
				if (g[i][j] > 9){
					System.out.print(g[i][j] + " ");
				} else {
					System.out.print(g[i][j] + "  ");
				}
			}
			System.out.println();
		}
		System.out.println();
	}

	public void loadModels() {
		singletonModel = new PruneModel();
		singletonModel.load(modelFileName + "." + singletonFileName);
		
		predicateModel = new PruneModel();
		predicateModel.load(modelFileName + "." + predicateFileName);
	}
	
	/** Do predictions and save them in the input sentence objects. */
	public void predict(){
		predictForInputSentences(singletonModel, predicateModel);
	}

	private void predictForInputSentences(PruneModel singletonModel, PruneModel predicateModel) {
		for (int i = 0; i < inputSentences.length; i++){
			int[] singles = predict(singletonModel, i);
			int[] preds = predict(predicateModel, i);
			// turns out singletons() and predicates() return the scala object's internal array representation, so you can stuff new values into them.
			copyValues(singles, inputSentences[i].singletons());
			copyValues(preds, inputSentences[i].predicates());
		}
	}

	private static void copyValues(int[] source, Integer[] dest) {
		assert source.length == dest.length;
		for (int i = 0; i < source.length; i++){
			dest[i] = source[i];
		}
	}

	/** return predicted labels, as integers
	 * todo eventually: clean up messiness with labels vs integers and all that.  why not just use the raw integer numberings? */
	private int[] predict(PruneModel model, int snum) {
        	List<Map<String, Set<String>>> feats = ghettoFeats(snum);
        	// run viterbi
    		Viterbi v = new Viterbi(model.weights);
    		String[] labelsAsStrings = v.decode(feats);
    		int[] predLabels = new int[labelsAsStrings.length-1];
    		for (int i = 1; i < labelsAsStrings.length; i++){
    			predLabels[i-1] = Integer.parseInt(labelsAsStrings[i]);
    		}
    		return predLabels;
	}
	
	

}
