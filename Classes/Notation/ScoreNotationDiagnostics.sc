// Note [A located diagnostic is the parser's half]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `Marking` owns the loudness rule. The parser owns the token and the
// source wording. Unclassified refusals are still reported with the
// parser's own message.

+ ScoreNotation {

    // Written-source issues as records. `ScoreIssue.prOf` owns the
    // shape. Parser rules stay here.
    //
    // >>> ScoreNotation.prNotationIssues("c4 d4:p:mf").first[\code]
    // loudnessDynamicRepeated
    // >>> ScoreNotation.prNotationIssues("c4 d4:sfz:pp").size   -> 0
    //
    // The label defaults to the parser's own, so a record carries the
    // sentence a caller would have been thrown rather than one naming
    // the checker.
    *prNotationIssues { |text, label = "Measure.notation"|
        var whole = text.asString;
        var tokens, failure;

        // A `try` goes around the call, never inside an argument list.
        try { tokens = this.prNotationTokens(whole, label) }
            { |err| failure = err.what };

        if (failure.notNil) {
            ^[ScoreIssue.prOf(\notationUnclassified, failure)]
        };

        ^tokens.inject([], { |all, token|
            var issues = this.prTokenIssues(token, whole, label);

            if (issues.isEmpty) {
                issues = this.prTokenRefusal(token, whole, label)
            };
            all ++ issues
        })
    }

    // If this walk finds no code, ask the parser and carry its
    // refusal. The permissive flags keep slot-only refusals out.
    *prTokenRefusal { |token, whole, label|
        var failure, issue;
        try { this.prNotationChild(token, whole, label, true, true, true) }
            { |err| failure = err.what };
        if (failure.isNil) { ^[] };
        // The parser already names the token. Add only the column key.
        ^[this.prAtToken(ScoreIssue.prOf(\notationUnclassified, failure),
            token)]
    }

    // The marks a bracket head adds to each child. Nil means no
    // bracket form claimed the head. [] means a real bracket with no
    // inherited marks.
    *prBracketHeadMarks { |token|
        var marking;
        if (this.prHairpinHead(token).notNil) { ^[] };
        if (this.prGlissandoHead(token).notNil) { ^[] };
        marking = this.prMarkingGroupHead(token);
        ^marking !? { marking[0] }
    }

    // One leaf token or the leaves inside a bracket with inherited
    // group marks. This walk reports loudness codes only. Unreadable
    // tokens are left for `prTokenRefusal`.
    *prTokenIssues { |token, whole, label, inherited|
        var at = this.prOutsideBraces(token, $[);
        var head, issue, inside, suffixes, failure;
        inherited = inherited ? [];
        if (at.notNil and: { at > 0 } and: { token.endsWith("]") }) {
            head = this.prBracketHeadMarks(token);
            if (head.isNil) {
                // Ask after bracket forms before the tuplet test.
                issue = this.prMarkingGroupHeadIssue(token, whole, label);
                if (issue.notNil) { ^[this.prAtToken(issue, token)] };
                if (this.prLooksLikeTupletToken(token)) { head = [] } { ^[] }
            };
            try {
                inside = this.prNotationTokens(
                    token.copyRange(at + 1, token.size - 2), label)
            } { |err| failure = err.what };
            if (failure.notNil) { ^[] };
            ^inside.inject([], { |all, each|
                all ++ this.prTokenIssues(each, whole, label,
                    inherited ++ head) })
        };
        try { suffixes = this.prLeafSuffixes(token, whole, label) }
            { |err| failure = err.what };
        if (failure.notNil) { ^this.prLeafSuffixIssues(token, whole, label) };
        ^Marking.prLoudnessIssues(inherited ++ suffixes[1]).collect { |issue|
            this.prLocated(issue, token, whole) }
    }

    // A coded suffix refusal, asked only after the suffix pass throws.
    *prLeafSuffixIssues { |token, whole, label|
        var at = this.prOutsideBraces(token, $:);
        var found, stopped = false;
        if (at.isNil or: { at == 0 }) { ^[] };
        // Stop where the parser stops.
        this.prSplitOutsideBraces(token.copyToEnd(at + 1), $:).do { |name|
            var open, suffix, failure;
            if (stopped.not) {
                open = name.indexOf(${);
                suffix = if (open.isNil) { name }
                    { name.copyRange(0, open - 1) };
                found = this.prAppoggiaturaIssue(suffix, token, whole, label);
                if (found.notNil) { stopped = true } {
                    // The two suffix readers, asked only for stop position.
                    try {
                        if (this.prGraceSuffixStyle(name, token, whole, label)
                            .isNil) {
                            this.prMarkingNamed(name, token, whole, label)
                        }
                    } { |err| failure = err.what };
                    if (failure.notNil) { stopped = true }
                }
            }
        };
        // A record is a Dictionary, so `asArray` would answer its values.
        if (found.isNil) { ^[] };
        ^[this.prAtToken(found, token)]
    }

    // Add the same source sentence `prRefuseMarks` would throw.
    *prLocated { |issue, token, whole|
        issue[\message] = "% It was written as %.".format(
            issue[\message], this.prLeafAt(token, whole));
        ^this.prAtToken(issue, token)
    }

    // The token an editor can point at.
    *prAtToken { |issue, token| ^ScoreIssue.prAtToken(issue, token) }
}
