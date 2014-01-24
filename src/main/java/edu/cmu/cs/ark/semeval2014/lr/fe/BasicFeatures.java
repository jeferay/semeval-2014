package edu.cmu.cs.ark.semeval2014.lr.fe;

import util.U;
import edu.cmu.cs.ark.semeval2014.common.InputAnnotatedSentence;
import edu.cmu.cs.ark.semeval2014.lr.fe.FE.FeatureAdder;

public class BasicFeatures extends FE.FeatureExtractor implements FE.TokenFE, FE.EdgeFE {

	@Override
	public void features(int word1, int word2, String label, FeatureAdder fa) {
//		String w1 = sent.sentence()[word1];
//		String w2 = sent.sentence()[word2];
//		fa.add(U.sf("lc:bg:%s_%s_%s", w1.toLowerCase(), w2.toLowerCase(), label));

		String p1 = sent.pos()[word1];
		String p2 = sent.pos()[word2];
		fa.add(U.sf("pos:bg:%s_%s_%s", p1, p2, label));
	}

	@Override
	public void features(int word1, FeatureAdder fa) {
		String p1 = sent.pos()[word1];
		fa.add(U.sf("pos:%s", p1));
//		String w1 = sent.pos()[word1].toLowerCase();
//		fa.add("lc:" + w1);
	}

}