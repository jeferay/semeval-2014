package edu.cmu.cs.ark.semeval2014.common.Train

import edu.cmu.cs.ark.semeval2014.common._
import scala.util.Random

class SSGD extends Optimizer {
    def learnParameters(gradient: Int => FeatureVector,
                        weights: FeatureVector,
                        trainingSize: Int,
                        passes: Int,
                        stepsize: Double,
                        avg: Boolean) : FeatureVector = {
        var avg_weights = new FeatureVector()
        for (i <- Range(1,passes+1)) {
            logger(0,"Pass "+i.toString)
            for (t <- Random.shuffle(Range(0, trainingSize).toList)) {
                weights -= stepsize * gradient(t)
            }
            avg_weights += weights
        }
        if(avg) { avg_weights } else { weights }
    }
}

