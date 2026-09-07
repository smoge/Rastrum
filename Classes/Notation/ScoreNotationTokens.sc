// Token and source-shape helpers for `ScoreNotation`.
//
// Split a line into bars, a bar into tokens and source positions for
// refusals. Nothing here knows what a token means.

+ ScoreNotation {

    // Top-level bar spans. Keep text unstripped for refusals. Split
    // outside prose, chords and brackets.
    *prNotationBars { |text|
        var spans = [], current = "", open = false, depth = 0, braces = 0;

        text.do { |char|
            case
            { braces > 0 } {
                if (char == ${) { braces = braces + 1 };
                if (char == $}) { braces = braces - 1 };
                current = current ++ char;
            }
            { char == ${ } { braces = braces + 1; current = current ++ char }
            { char == $< } { open = true;  current = current ++ char }
            { char == $> } { open = false; current = current ++ char }
            { char == $[ } { depth = depth + 1; current = current ++ char }
            { char == $] } { depth = max(0, depth - 1); current = current ++ char }
            { char == $| and: { open.not } and: { depth == 0 } } {
                spans = spans.add(current); current = "" }
            { true } { current = current ++ char };
        };
        ^spans.add(current)
    }

    // Up to the first whitespace. Tabs and newlines count too.
    *prFirstToken { |text|
        var at = 0;
        while { at < text.size and: { text[at].isSpace.not } } { at = at + 1 };
        ^text.copyRange(0, at - 1)
    }

    // The meter and bar of one written line, both stripped, plus
    // whether the separator was `;;`. Nil when there is no semicolon
    // to split at.
    *prNotationSplit { |line|
        var at = this.prNotationSemicolon(line);
        var pickup, bodyAt;
        if (at.isNil) { ^nil };
        pickup = (at < (line.size - 1)) and: { line[at + 1] == $; };
        bodyAt = if (pickup) { at + 2 } { at + 1 };
        ^[
            if (at > 0) { line.copyRange(0, at - 1).stripWhiteSpace } { "" },
            if (bodyAt < line.size) {
                line.copyRange(bodyAt, line.size - 1).stripWhiteSpace } { "" },
            pickup
        ]
    }

    // Everything between braces is prose, as it is for
    // `prNotationTokens`.
    *prNotationSemicolon { |text|
        var i = 0, size = text.size, braces = 0;

        while { i < size } {
            case
            { braces > 0 } {
                if (text[i] == ${) { braces = braces + 1 };
                if (text[i] == $}) { braces = braces - 1 };
            }
            { text[i] == ${ } { braces = braces + 1 }
            { text[i] == $; } { ^i };
            i = i + 1;
        };
        ^nil
    }

    // Whitespace separates leaves only at top level. Chords, brackets
    // and text braces keep their own spaces.
    *prNotationTokens { |text, label|
        var tokens = [], current = "", open = false, depth = 0, braces = 0;
        var flush = { if (current.notEmpty) { tokens = tokens.add(current) };
            current = "" };

        text.do { |char|
            case
            // Inside braces everything is prose.
            { braces > 0 } {
                if (char == ${) { braces = braces + 1 };
                if (char == $}) { braces = braces - 1 };
                current = current ++ char;
            }
            { char == ${ } { braces = braces + 1; current = current ++ char }
            { char == $} } {
                    Error(
                        "%: \"%\" closes a brace that never opened. Use text suffixes "
                        "like \"c4:text{sul pont.}\"."
                        .format(label, text)
                    ).throw
            }
            { char == $< } {
                if (open) {
                    Error(
                        "%: \"%\" opens a chord inside a chord."
                        .format(label, text)
                    ).throw
                };
                open = true;
                current = current ++ char;
            }
            { char == $> } {
                if (open.not) {
                    Error(
                        "%: \"%\" closes a chord that never opened. Use <c e g>4."
                        .format(label, text)
                    ).throw
                };
                open = false;
                current = current ++ char;
            }
            // Brackets nest. Chords do not.
            { char == $[ } { depth = depth + 1; current = current ++ char }
            { char == $] } {
                if (depth == 0) {
                    Error(
                        "%: \"%\" closes a bracket that never opened. Use 3:2[c4 d4 e4]."
                        .format(label, text)
                    ).throw
                };
                depth = depth - 1;
                current = current ++ char;
            }
            { open.not and: { depth == 0 } and: { char.isSpace } } { flush.value }
            { true } { current = current ++ char };
        };
        if (open) {
            Error(
                "%: \"%\" leaves a chord unclosed. Use <c e g>4."
                .format(label, text)
            ).throw
        };
        if (depth > 0) {
            Error(
                "%: \"%\" leaves a bracket unclosed. Use 3:2[c4 d4 e4]."
                .format(label, text)
            ).throw
        };
        if (braces > 0) {
            Error(
                "%: \"%\" leaves braces unclosed. Use text suffixes like \"c4:text{sul pont.}\"."
                .format(label, text)
            ).throw
        };
        flush.value;
        ^tokens
    }

    // Braces let prose contain spaces, colons and brackets.
    // `text{...}` is the default side. `textAbove` and `textBelow`
    // name one.
    *prOutsideBraces { |text, char|
        var braces = 0, at;
        text.do { |each, index|
            if (at.isNil) {
                case
                { each == ${ } { braces = braces + 1 }
                { each == $} } { braces = max(0, braces - 1) }
                { each == char and: { braces == 0 } } { at = index }
            }
        };
        ^at
    }

    *prSplitOutsideBraces { |text, char|
        var parts = [], current = "", braces = 0;
        text.do { |each|
            case
            { each == ${ } { braces = braces + 1; current = current ++ each }
            { each == $} } { braces = max(0, braces - 1); current = current ++ each }
            { each == char and: { braces == 0 } } {
                parts = parts.add(current); current = "" }
            { true } { current = current ++ each }
        };
        ^parts.add(current)
    }

    // Brace balance for tokens that build themselves.
    *prCheckBraces { |token, whole, label|
        var braces = 0;
        token.do { |char|
            if (char == ${) { braces = braces + 1 };
            if (char == $}) {
                braces = braces - 1;
                if (braces < 0) {
                    Error(
                        "%: % closes a brace that never opened. Use text suffixes like "
                        "\"c4:text{sul pont.}\"."
                        .format(label, this.prLeafAt(token, whole))
                    ).throw
                }
            }
        };
        if (braces > 0) {
            Error(
                "%: % leaves braces unclosed. Use text suffixes like \"c4:text{sul pont.}\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        }
    }

    *prRefuseStrayBraces { |part, token, whole, label|
        if (part.includes(${) or: { part.includes($}) }) {
            Error(
                "%: % has braces without a text suffix. Use \"c4:text{sul pont.}\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        }
    }

    *prIsRatioToken { |token|
        var at = this.prOutsideBraces(token, $:);
        if (at.isNil or: { at == 0 }) { ^false };
        ^token.copyRange(0, at - 1).every { |char| char.isDecDigit }
    }

    // Name the bar only when there is more than one to tell apart.
    *prBarAt { |text, at|
        if (at.isNil) { ^"\"%\"".format(text) };
        ^"bar %, \"%\",".format(at + 1, text)
    }

    // Name the leaf alone only when it is the whole source.
    *prLeafAt { |token, whole|
        if (token == whole) { ^"\"%\"".format(token) };
        ^"\"%\" in \"%\"".format(token, whole)
    }
}
