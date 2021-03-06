package edu.cmu.cs.ark.semeval2014.amr.GraphDecoder


import java.lang.Math.abs
import java.lang.Math.max
import edu.cmu.cs.ark.semeval2014.common.{logger, FeatureVector}
import edu.cmu.cs.ark.semeval2014.amr.{Input, DecoderResult}
import edu.cmu.cs.ark.semeval2014.amr.graph.Graph

class LagrangianRelaxation(featureNames: List[String], labelSet: Array[(String, Int)], stepsize: Double, maxIterations: Int)
    extends Decoder(featureNames) {
    // Base class has defined:
    // val features: Features

    val alg2 = new Alg2("labelWithId" :: featureNames, labelSet)

    val labelConstraint = labelSet.toMap
    val IdLabel = """Id1.*[+]L=(.*)""".r

    private var inputSave: Input = _
    def input : Input = inputSave
    def input_= (i: Input) {
        inputSave = i
        features.input = i
        alg2.features.weights = features.weights    // Set alg2's weights same our weights (shared weights) (This must be done before we set alg2.input, because it precomputes edge weights using its feature weights)
        alg2.input = i
    }


    def decode() : DecoderResult = {
        var result = DecoderResult(Graph.empty(), new FeatureVector(), 0.0)

        val multipliers = new FeatureVector()
        var delta = 0.0     // so we know when we have converged
        var counter = 0
        do {
            //logger(2, "weights: \n"+features.weights)
            logger(1, "multipliers: \n"+multipliers.toString)
            features.weights -= multipliers
            //logger(2, "alg2 weights: \n"+features.weights)
            result = alg2.decode
            logger(1, "id features: \n"+result.features.slice(x => x.startsWith("Id1=")))
            features.weights += multipliers // undo our adjustment to the weights

            delta = 0.0
            for ((feat, value) <- result.features.fmap if feat.startsWith("Id1=")) {
                val multiplier = multipliers.fmap.getOrElse(feat, 0.0)
                val IdLabel(label) = feat
                val newMultiplier = max(0.0, multiplier - stepsize * (labelConstraint(label) - value))
                delta += abs(newMultiplier - multiplier)
                multipliers.fmap(feat) = newMultiplier
            }
            counter += 1
        } while (delta != 0.0 && counter < maxIterations)

        if (delta != 0.0) {
            logger(0, "WARNING: Langrangian relaxation did not converge after "+counter.toString+" iterations. Delta = "+delta.toString)
        } else {
            logger(0, "Langrangian relaxation converged after "+counter.toString+" iterations. Delta = "+delta.toString)
        }

        val feats = result.features.slice(x => !x.startsWith("Id1="))
        return DecoderResult(result.graph, feats, features.weights.dot(feats))
    }
}

