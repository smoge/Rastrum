// Note [One slot, and it is not ours]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `thisProcess.interpreter.preProcessor` is one variable for the
// whole interpreter. This is off by default, refuses an occupied slot
// unless forced, and puts back what it found.
//
// It reaches only what the interpreter is handed: an editor
// evaluation or a loaded file, never a compiled class file and never
// `"...".interpret`.

// Note [A block is only a block outside quoted text]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Rewriting is textual, so Strings, symbols, character literals and
// comments are copied through. A block is `[tag|` with no space and a
// closing `|]`; the sclang array `[a | b]` is not one.


// RastrumQuasiquoter
//
// Opt-in front end. It rewrites a tagged block into the constructor
// call it names:
//
//   [note|     c-[5]8.          |]  ->  MusicNote.notation("c-[5]8.")
//   [run|      c4 r4 <e g>2     |]  ->  ScoreNotation.leafRun("c4 r4 <e g>2")
//   [measure|  4/4 c4 d4 e2     |]  ->  Measure.notation("4/4", "c4 d4 e2")
//   [measures| 2/4 c2 | d2      |]  ->  ScoreNotation.measureRun("2/4 c2 | d2")
//   [rtm|      (1 (1 (1 1)) 2)  |]  ->  RhythmCell.rtm("(1 (1 (1 1)) 2)")
//
// It owns no grammar. Contents go to the parser the named constructor
// already uses, so `[score| ... |]` is absent.
RastrumQuasiquoter {
    // What `start` found in the slot, and what it put there. Both nil
    // when nothing is installed.
    classvar saved, installed;

    // The tags, and the only ones. `prExpand` answers each.
    //
    // Smallest result first: leaf, leaf run, bar, bar run, rhythm.
    //
    // >>> RastrumQuasiquoter.tags   -> [ note, run, measure, measures, rtm ]
    *tags { ^["note", "run", "measure", "measures", "rtm"] }

    // Source in, source out. Pure, so tags can be tested without installing.
    //
    // >>> RastrumQuasiquoter.translate("[rtm| (1 2) |]").contains("RhythmCell.rtm")
    // true
    // >>> RastrumQuasiquoter.translate("[note| c-[5]8. |]").contains("MusicNote.notation")
    // true
    // >>> RastrumQuasiquoter.translate("[run| c4 d4 |]").contains("ScoreNotation.leafRun")
    // true
    *translate { |source|
        var out, index = 0, size, char, verbatim, block;

        if (source.isKindOf(String).not) {
            Error("RastrumQuasiquoter.translate: source must be a String, got % "
                "(%).".format(source, source.class)).throw
        };
        // Leave early unless a block may be present. A known opener
        // still reaches the refusal path.
        if (source.find("|]").isNil and: { this.prOpensATag(source).not }) {
            ^source
        };

        out = "";
        size = source.size;
        while { index < size } {
            char = source[index];
            verbatim = this.prVerbatimEnd(source, index);
            case
            { verbatim.notNil } {
                out = out ++ source.copyRange(index, verbatim - 1);
                index = verbatim;
            }
            { char == $[ } {
                block = this.prBlockAt(source, index);
                if (block.isNil) {
                    out = out ++ char;
                    index = index + 1;
                } {
                    out = out ++ block[0];
                    index = block[1];
                }
            }
            { true } {
                out = out ++ char;
                index = index + 1;
            };
        };
        ^out
    }

    *prOpensATag { |source|
        ^this.tags.any { |tag| source.find("[" ++ tag ++ "|").notNil }
    }

    // The end of quoted/comment text starting at `index`, or nil.
    // See Note [A block is only a block outside quoted text].
    *prVerbatimEnd { |source, index|
        var char = source[index];
        var next = if (index + 1 < source.size) { source[index + 1] } { nil };
        var doubleQuote = RastrumChar.doubleQuote, singleQuote = RastrumChar.singleQuote;
        var backslash = RastrumChar.backslash;

        if (char == doubleQuote) {
            ^this.prQuotedEnd(source, index, doubleQuote) ?? { source.size }
        };
        if (char == singleQuote) {
            ^this.prQuotedEnd(source, index, singleQuote) ?? { source.size }
        };
        // Escaped character literals are three characters.
        if (char == $$) {
            if (next == backslash) { ^min(index + 3, source.size) };
            ^min(index + 2, source.size)
        };
        if (char != $/) { ^nil };
        if (next == $/) { ^this.prLineEnd(source, index) };
        if (next == $*) { ^this.prBlockCommentEnd(source, index) };
        ^nil
    }

    // Just past the closing `quote`, or nil when there is no closing one.
    *prQuotedEnd { |source, index, quote|
        var i = index + 1, size = source.size, char;
        var backslash = RastrumChar.backslash;

        while { i < size } {
            char = source[i];
            if (char == backslash) {
                i = i + 2
            } {
                if (char == quote) { ^i + 1 };
                i = i + 1;
            };
        };
        ^nil
    }

    *prLineEnd { |source, index|
        var at = source.find("\n", false, index);
        ^if (at.isNil) { source.size } { at + 1 }
    }

    // sclang's block comments nest, so this counts rather than finds.
    *prBlockCommentEnd { |source, index|
        var i = index + 2, size = source.size, depth = 1;

        while { i + 1 < size and: { depth > 0 } } {
            case
            { source[i] == $/ and: { source[i + 1] == $* } } {
                depth = depth + 1; i = i + 2 }
            { source[i] == $* and: { source[i + 1] == $/ } } {
                depth = depth - 1; i = i + 2 }
            { true } { i = i + 1 };
        };
        ^if (depth > 0) { size } { i }
    }

    // `[expansion, index after the block]`, or nil when no block
    // starts here. Refuse only text that has block shape.
    *prBlockAt { |source, index|
        var i = index + 1, size = source.size, tag, where, bodyEnd, body;

        while { i < size and: { source[i].isAlpha } } { i = i + 1 };
        // sclang reads left to right; the parentheses are load-bearing.
        if (i == (index + 1)) { ^nil };
        if (i >= size or: { source[i] != $| }) { ^nil };

        tag = source.copyRange(index + 1, i - 1);
        where = this.prWhere(source, index);
        // `includes` compares by identity, which no two Strings are.
        if (this.tags.any { |each| each == tag }.not) {
            // Unknown openers without `|]` belong to ordinary code.
            if (source.find("|]", false, i).isNil) { ^nil };
            Error("RastrumQuasiquoter: [%| ... |] % uses unknown tag %. Tags: %."
                .format(tag, where, tag.asCompileString,
                    this.tags.join(", "))).throw
        };

        // A known tag must close here.
        bodyEnd = this.prBodyEnd(source, i + 1, tag, where);
        body = source.copyRange(i + 1, bodyEnd - 1);
        ^[
            this.prExpand(tag, body, where)
                ++ this.prPadding(source.copyRange(index, bodyEnd + 1)),
            bodyEnd + 2
        ]
    }

    // The `|` of the closing `|]`.
    //
    // Braces hold notation prose, so `|]` inside them is text.
    // Outside braces, quotation marks are sclang syntax and are
    // checked here.
    *prBodyEnd { |source, start, tag, where|
        var i = start, size = source.size, braces = 0, closed, char;
        var quote = RastrumChar.doubleQuote;

        while { i < size } {
            char = source[i];
            case
            { braces > 0 } {
                if (char == ${) { braces = braces + 1 };
                if (char == $}) { braces = braces - 1 };
                i = i + 1;
            }
            { char == ${ } { braces = braces + 1; i = i + 1 }
            { char == quote } {
                closed = this.prQuotedEnd(source, i, quote);
                if (closed.isNil) {
                    Error("RastrumQuasiquoter: [%| ... |] % leaves a quotation "
                        "open. Close it or remove it.".format(tag, where)).throw
                };
                i = closed;
            }
            { char == $| and: { i + 1 < size } and: { source[i + 1] == $] } } { ^i }
            { true } { i = i + 1 };
        };
        Error("RastrumQuasiquoter: [%| ... |] % never closes. A block ends with "
            "|].".format(tag, where)).throw
    }

    // Preserve line numbers after a multi-line block becomes one String.
    *prPadding { |consumed|
        var lines = consumed.select { |char| char == Char.nl }.size;
        ^String.fill(lines, { Char.nl })
    }

    // Note [Two kinds of refusal, and one of them carries a location]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Block errors are refused here and get a line/column. Notation
    // errors come from the parser the expansion calls. Generated code
    // stays the same as the source-level call.

    *prExpand { |tag, body, where|
        var text = body.stripWhiteSpace;

        case
        { tag == "note" } { ^"MusicNote.notation(%)".format(this.prQuoted(text)) }
        // A run answers an Array; the tag already says that.
        { tag == "run" } { ^"ScoreNotation.leafRun(%)".format(this.prQuoted(text)) }
        // Let `ScoreNotation.measureRun` own meter and barline parsing.
        { tag == "measures" } {
            ^"ScoreNotation.measureRun(%)".format(this.prQuoted(text)) }
        { tag == "rtm" } { ^"RhythmCell.rtm(%)".format(this.prQuoted(text)) }
        { tag == "measure" } { ^this.prMeasure(text, where) };
        // Guard tag-table drift.
        Error("RastrumQuasiquoter: % is in tags and has no expansion."
            .format(tag.asCompileString)).throw
    }

    // Use `ScoreNotation.prNotationMeterAndBar` so the grammar has
    // one home. Missing halves are named here because only this side
    // knows the location.
    *prMeasure { |text, where|
        var split = ScoreNotation.prNotationMeterAndBar(text);

        if (split.isNil) {
            Error("RastrumQuasiquoter: [measure| ... |] % states no meter. "
                "Use [measure| 4/4 c4 d4 e2 |].".format(where)).throw
        };
        if (split[0].isEmpty or: { split[1].isEmpty }) {
            Error("RastrumQuasiquoter: [measure| ... |] % has no %. Use "
                "[measure| 4/4 c4 d4 e2 |].".format(where,
                    if (split[0].isEmpty) { "meter" } { "bar" })).throw
        };
        ^"Measure.notation(%, %)".format(
            this.prQuoted(split[0]), this.prQuoted(split[1]))
    }

    // A String literal spelling exactly `text`.
    //
    // >>> RastrumQuasiquoter.prQuoted("a b").asCompileString   -> "\"a b\""
    *prQuoted { |text|
        var out = "\"";
        var quote = RastrumChar.doubleQuote, backslash = RastrumChar.backslash;

        text.do { |char|
            case
            { char == backslash } { out = out ++ "\\\\" }
            { char == quote } { out = out ++ "\\\"" }
            { char == Char.nl } { out = out ++ "\\n" }
            { char == Char.ret } { out = out ++ "\\r" }
            { char == Char.tab } { out = out ++ "\\t" }
            { true } { out = out ++ char };
        };
        ^out ++ "\""
    }

    // Where a block begins in source text.
    //
    // >>> RastrumQuasiquoter.prWhere("a\nbc", 2)   -> at line 2, column 1
    *prWhere { |source, index|
        var line = 1, col = 1, i = 0;

        while { i < index } {
            if (source[i] == Char.nl) { line = line + 1; col = 1 } { col = col + 1 };
            i = i + 1;
        };
        ^"at line %, column %".format(line, col)
    }

    // What `start` installs, fresh each time.
    *preProcessor { ^{ |code| RastrumQuasiquoter.translate(code) } }

    *active {
        ^installed.notNil and: {
            thisProcess.interpreter.preProcessor === installed }
    }

    // See Note [One slot, and it is not ours]. `force` replaces
    // whatever is there, and `stop` still puts that back.
    *start { |force = false|
        var current;

        if (this.active) { ^this };
        current = thisProcess.interpreter.preProcessor;
        if (current.notNil and: { force.not }) {
            Error("Rastrum: the interpreter already has a preprocessor. Stop it "
                "first, or call Rastrum.startQuasiquoter(true) to replace it.")
                .throw
        };
        saved = current;
        installed = this.preProcessor;
        thisProcess.interpreter.preProcessor = installed;
        ^this
    }

    // Harmless when nothing is installed. Refuse to overwrite a later
    // preprocessor.
    *stop {
        if (this.active.not) {
            if (installed.notNil) {
                warn("Rastrum: the quasiquoter is no longer the installed "
                    "preprocessor, so something else replaced it. Restoring "
                    "would take that away. Nothing changed.")
            };
            saved = nil;
            installed = nil;
            ^this
        };
        thisProcess.interpreter.preProcessor = saved;
        saved = nil;
        installed = nil;
        ^this
    }
}
