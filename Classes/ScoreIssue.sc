// ScoreIssue: a refusal said as data.
//
// A refusal still throws prose. The record beside it gives tools a
// stable `code`. The throwing path uses the same rule.
//
// This class owns the vocabulary and record shape. Rules stay with the
// facts they judge: `Marking`, `ScoreNotation` and `ScoreEdit`.
//
// Private while the diagnostic lane is an experiment.
ScoreIssue {
    classvar severities;

    *initClass {
        severities = IdentityDictionary[
            // Loudness on a leaf, owned by `Marking`.
            \loudnessDynamicRepeated               -> \error,
            \loudnessSforzandoRepeated             -> \error,
            \loudnessOrderReversed                 -> \error,
            // Bracket heads, owned by `ScoreNotation`.
            \graceAppoggiaturaUnsupported          -> \error,
            \markingGroupGraceHeadOutOfPlace       -> \error,
            \markingGroupOpenReserved              -> \error,
            \markingGroupSforzandoMissingLevel     -> \error,
            \markingGroupRinforzandoOutOfPlace     -> \error,
            \markingGroupDynamicOutOfPlace         -> \error,
            \markingGroupCompoundMissingDynamic    -> \error,
            \markingGroupCompoundTooManyParts      -> \error,
            \markingGroupCompoundSettleNotADynamic -> \error,
            // Edit refusals, owned by `ScoreEdit`.
            \beamNeedsTwoHeads                     -> \error,
            \beamRestSelected                      -> \error,
            \beamDurationTooLong                   -> \error,
            \spanEndpointSelected                  -> \error,
            \spanInteriorSelected                  -> \error,
            // A refusal with no narrower code.
            \notationUnclassified                  -> \error
        ];
    }

    // The inventory lists every code and its default severity. Every
    // one is an error today.
    //
    // A record for one mistake. Undeclared codes stop here.
    //
    // >>> ScoreIssue.prOf(\loudnessOrderReversed, "x")[\severity]   -> error
    // >>> ScoreIssue.prOf(\beamRestSelected, "x")[\code]   -> beamRestSelected
    *prOf { |code, message|
        var word = code.asSymbol;
        var severity = severities[word];
        if (severity.isNil) {
            Error(
                "ScoreIssue: \"%\" is not a diagnostic code.".format(code)
            ).throw
        };
        ^IdentityDictionary[
            \code -> word, \severity -> severity, \message -> message]
    }

    // Every declared code, sorted for the contract test.
    //
    // >>> ScoreIssue.prCodes.includes(\notationUnclassified)   -> true
    *prCodes {
        ^severities.keys.asArray.sort { |a, b| a.asString < b.asString }
    }

    // A source check may know a token. A value check may know a
    // `ScoreSelection` path. Neither is guaranteed.
    //
    // >>> ScoreIssue.prAtToken(ScoreIssue.prOf(\beamRestSelected, "x"), "c4")[\sourceText]   -> c4
    *prAtToken { |issue, token|
        issue[\sourceText] = token;
        ^issue
    }

    // >>> ScoreIssue.prAtPath(ScoreIssue.prOf(\beamRestSelected, "x"), [0, 0, 1])[\path]   -> [ 0, 0, 1 ]
    *prAtPath { |issue, path|
        issue[\path] = path;
        ^issue
    }
}
