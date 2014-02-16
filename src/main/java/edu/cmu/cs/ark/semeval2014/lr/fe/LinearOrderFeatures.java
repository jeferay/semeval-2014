package edu.cmu.cs.ark.semeval2014.lr.fe;

import edu.cmu.cs.ark.semeval2014.lr.fe.FE.FeatureAdder;
import util.U;
import java.lang.Math.abs;
import java.lang.Math.log;
import java.lang.Math.floor;
import java.lang.Math.sign;

public class LinearOrderFeatures extends FE.FeatureExtractor implements FE.EdgeFE {

	@Override
	/**
	 * Feature for the directed linear distance between a child and its parent.
	 * Motivation: ARG1 usually on the left, ARG2 on the right, etc.
	 */
	public void features(int srcTokenIdx, int destTokenIdx, FeatureAdder fa) {
		int dist = srcTokenIdx - destTokenIdx;
		fa.add(U.sf("lin:%s", dist));
		fa.add(U.sf("left:%s", (srcTokenIdx < destTokenIdx)));
        fa.add(U.sf("logD:%s", sign(dist)*floor(log(abs(dist)+1)/log(1.39))));
	}
}
