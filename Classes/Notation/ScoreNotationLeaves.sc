// Leaf parsing for `ScoreNotation`.
//
// One written token into one leaf: pitch spelling and register,
// length and tie, plus suffixes: marks, text and a grace group.
// Brackets and bars stay with the grammar.

+ ScoreNotation {

    // Pitch spelling, register marks then length. `rests` is caller policy.
    *prNotationLeaf { |token, whole, label = "Measure.notation", rests = true|
        // The token as written, since suffix and tie passes rewrite `token`.
        var written = token;
        var at, head, tail, octave, tie, tied, star, value;
        // Suffixes come off first.
        var marks = this.prLeafSuffixes(token, whole, label);
        var markings = marks[1];
        var group = marks[2];
        token = marks[0];
        tie = this.prTieSuffix(token, whole, label);
        tied = tie[1];
        token = tie[0];
        at = token.size;
        // `Chord` owns the inside of `<...>`.
        if (token.beginsWith("<")) {
            ^this.prGraced(
                this.prMarked(
                    Chord.notation(token, tied, label,
                        this.prLeafAt(token, whole)),
                    markings, this.prLeafAt(written, whole)),
                group)
        };
        // Starred lengths use rational duration syntax.
        star = token.indexOf($*);
        if (star.isNil) {
            if (token.includes($/)) {
                Error(
                    "%: % writes a rational length without `*`. Use \"%\"."
                    .format(
                        label,
                        this.prLeafAt(token, whole),
                        this.prRationalLeafExample(token, label)
                    )
                ).throw
            };
            while { at > 0 and: { token[at - 1] == $. } } { at = at - 1 };
            while { at > 0 and: { token[at - 1].isDecDigit } } { at = at - 1 };
            head = token.copyRange(0, at - 1);
            tail = token.copyRange(at, token.size - 1);
        } {
            head = token.copyRange(0, star - 1);
            tail = token.copyToEnd(star);
        };
        if (head.isEmpty or: { tail.isEmpty }) {
            ^this.prRefuseLeaf(token, whole, label, rests)
        };
        value = this.prTailDuration(tail, token, whole, label);
        octave = this.prOctaveMarks(head, token, whole, label);
        head = head.copyRange(0, head.size - 1 - octave[1]);
        if (head.isEmpty) { ^this.prRefuseLeaf(token, whole, label, rests) };
        if (head == "r" or: { head == "R" }) {
            if (rests.not) {
                Error(
                    "%: % is a rest. Use MusicRest or allow rests in this slot."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            if (octave[1] > 0) {
                Error(
                    "%: % puts an octave mark on a rest."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            if (tied) {
                Error(
                    "%: % ties a rest. Only notes and chords carry ties."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            ^this.prGraced(
                this.prMarked(MusicRest(value), markings,
                    this.prLeafAt(written, whole)), group)
        };
        ^this.prGraced(
            this.prMarked(
                MusicNote(MusicPitch(head, octave: octave[0]), value, tied),
                markings, this.prLeafAt(written, whole)),
            group)
    }

    // `c4` uses note-value grammar. `c*5/8` uses rational duration
    // grammar. The star is the separator, shared with
    // `Chord.notation`.
    *prTailDuration { |tail, token, whole, label = "Measure.notation"|
        var value;
        if (tail.beginsWith("*").not) {
            if (tail.contains("/")) {
                Error(
                    "%: % writes a rational length without `*`. Use \"%\"."
                    .format(
                        label,
                        this.prLeafAt(token, whole),
                        this.prRationalLeafExample(token, label)
                    )
                ).throw
            };
            ^Duration.lily(tail)
        };
        if (tail.size < 2) {
            Error(
                "%: % has `*` with no rational length. Use \"%\"."
                .format(
                    label,
                    this.prLeafAt(token, whole),
                    this.prRationalLeafExample(token, label)
                )
            ).throw
        };
        if (tail.contains("/").not) {
            Error(
                "%: % has `*` but no slash rational. Use \"%\" or a note value such as "
                "\"c4\"."
                .format(
                    label,
                    this.prLeafAt(token, whole),
                    this.prRationalLeafExample(token, label)
                )
            ).throw
        };
        value = Duration(tail.drop(1));
        // Written leaves have positive duration. Silence is a rest.
        if (value.numerator <= 0) {
            Error(
                "%: % lasts %. A leaf duration must be positive. Use a rest for "
                "silence."
                .format(label, this.prLeafAt(token, whole), value)
            ).throw
        };
        ^value
    }

    *prRationalLeafExample { |token, label|
        var text = token.asString.stripWhiteSpace;
        if (label == "MusicRest") { ^"r*3/8" };
        if (text.beginsWith("r") or: { text.beginsWith("R") }) { ^"r*3/8" };
        if (text.beginsWith("<")) { ^"<c e g>*5/8" };
        ^"c*5/8"
    }


    // Note [A marking is a suffix on the note]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `c4:mf:staccato` is a leaf plus point markings. Bare names come
    // from `Marking`.
    //
    // Text takes braces. Spanners stay object methods.
    //
    // A colon starts suffixes unless the token begins as a tuplet ratio.


    // Note [A grace group is a suffix on its host]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `c4:grace{b8 a8}` is one suffix on its host. The head names the
    // style, `grace` or `acciaccatura`. The body is a leaf run
    // attached through `ScoreLeaf.grace`.
    //
    // The model stores the group on the host, so there is no
    // forward-binding `grace[b8] c4` form.


    // Answers [the token without suffixes, the markings in written order,
    // and [grace leaves, style] or nil].
    *prLeafSuffixes { |token, whole, label = "Measure.notation", graces = true|
        var at, base, markings = [], group;

        // Chords and rests build themselves, so check braces here too.
        this.prCheckBraces(token, whole, label);
        at = this.prOutsideBraces(token, $:);
        if (at.isNil) {
            this.prRefuseStrayBraces(token, token, whole, label);
            ^[token, [], nil]
        };
        base = token.copyRange(0, at - 1);
        if (base.isEmpty) { ^this.prRefuseLeaf(token, whole, label) };
        this.prRefuseStrayBraces(base, token, whole, label);
        this.prSplitOutsideBraces(token.copyToEnd(at + 1), $:).do { |name|
            var style = this.prGraceSuffixStyle(name, token, whole, label, graces);
            if (style.isNil) {
                markings = markings.add(
                    this.prMarkingNamed(name, token, whole, label))
            } {
                if (group.notNil) {
                    Error(
                        "%: % writes two grace groups. A leaf has one, so put every grace leaf in one body."
                        .format(label, this.prLeafAt(token, whole))
                    ).throw
                };
                group = [this.prGraceBody(name, token, whole, label), style]
            }
        };
        ^[base, markings, group]
    }

    // The style a grace suffix names, or nil where the name is no
    // grace suffix. A head that names one and then says nothing
    // usable is refused here.
    //
    // See Note [A grace group is a suffix on its host].
    *prGraceSuffixStyle { |name, token, whole, label, graces = true|
        var open = name.indexOf(${);
        var head = if (open.isNil) { name } { name.copyRange(0, open - 1) };
        var style = this.prGraceStyleNamed(head);
        if (style.isNil) {
            this.prRefuseAppoggiatura(head, token, whole, label);
            ^nil
        };
        if (graces.not) {
            Error(
                "%: % carries a grace group. A pitch list names attacks only. Write the ornament in a bar instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (open.isNil) {
            Error(
                "%: % names a grace group with no braced leaves. Use \"c4:%{b8}\"."
                .format(label, this.prLeafAt(token, whole), head)
            ).throw
        };
        ^style
    }

    // The grace style a head spells or nil. `ScoreLeaf` owns the
    // vocabulary.
    //
    // >>> ScoreNotation.prGraceStyleNamed("acciaccatura")   -> acciaccatura
    // >>> ScoreNotation.prGraceStyleNamed("mordent")        -> nil
    *prGraceStyleNamed { |head|
        ^ScoreLeaf.graceStyles.detect { |each| head == each.asString }
    }

    // Refuse appoggiatura by name rather than as an unknown suffix.
    // Both doors reach this: `appoggiatura[b8]` and
    // `c4:appoggiatura{b8}`.
    //
    // The fact is named once and neither door owns the code.
    *prRefuseAppoggiatura { |head, token, whole, label|
        var issue = this.prAppoggiaturaIssue(head, token, whole, label);
        if (issue.isNil) { ^this };
        Error(issue[\message]).throw
    }

    *prAppoggiaturaIssue { |head, token, whole, label|
        if (head != "appoggiatura") { ^nil };
        ^ScoreIssue.prOf(\graceAppoggiaturaUnsupported,
            "%: % writes an appoggiatura, which the model does not carry. Use "
            "\"c4:grace{b8}\" or \"c4:acciaccatura{b8}\".".format(
                label, this.prLeafAt(token, whole)))
    }

    // The leaves a grace body holds, read as the leaf run it is.
    *prGraceBody { |name, token, whole, label|
        var body = this.prBracedBody(name, token, whole, label, "c4:grace{b8}");
        if (body.stripWhiteSpace.isEmpty) {
            Error(
                "%: % has an empty grace group. Use \"c4:grace{b8}\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        ^this.prNotationLeaves(body, label, false)
    }

    // A parsed group reaches its host through the model's own entry
    // point. The style is set where `ScoreLeaf.grace` sets it.
    *prGraced { |leaf, group|
        if (group.isNil) { ^leaf };
        ^leaf.perform(group[1], group[0])
    }

    // Note [A pitch list says no duration]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Pitch-list slots split rhythm from pitch. Tokens may carry
    // marking suffixes, but no duration, tie, rest, chord, tuplet or
    // group.


    // Answers `pitch -> markings`, avoiding two-element Array pitch specs.
    *prMarkedPitches { |text, label|
        var tokens = this.prNotationTokens(text, label);
        if (tokens.isEmpty) {
            Error(
                "%: \"%\" contains no pitch tokens. Use spaces, e.g. \"c e g\"."
                .format(label, text)
            ).throw
        };
        ^tokens.collect { |token|
            var marks, base;
            token = token.stripWhiteSpace;
            this.prRefuseWrittenLeaf(token, text, label);
            marks = this.prLeafSuffixes(token, text, label, false);
            base = marks[0];
            this.prRefuseLength(base, token, text, label);
            MusicPitch(base) -> marks[1]
        }
    }

    // Name non-pitch shapes before suffix parsing.
    *prRefuseWrittenLeaf { |token, whole, label|
        if (token.beginsWith("<")) {
            Error(
                "%: % is a chord. A pitch list names one pitch per attack, so a chord "
                "is written out in a bar instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (this.prLooksLikeTupletToken(token)) {
            Error(
                "%: % is a tuplet bracket. A pitch list names one pitch per attack and "
                "the rhythm list says the durations, so a bracket is written out in a "
                "bar instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (this.prHairpinHead(token).notNil) {
            Error(
                "%: % is a hairpin group. A pitch list cycles and a repeated endpoint "
                "says nothing, so a span is written out in a bar or attached with "
                "Spanner instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (this.prGlissandoHead(token).notNil) {
            Error(
                "%: % is a glissando group. A pitch list cycles, so write the run as a "
                "bar or attach it with Spanner instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        // Name marking groups before pitch parsing.
        if (this.prMarkingGroupHead(token).notNil) {
            Error(
                "%: % is a marking group. A pitch list cycles. Write the mark as "
                "suffixes, e.g. \"c:stac d:stac\", or write the run as a bar."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        // A bare ratio is a bracket typo here too.
        if (this.prIsRatioToken(token)) {
            Error(
                "%: % is a tuplet ratio. A pitch list names one pitch per attack and "
                "the rhythm list says the durations, so a bracket is written out in a "
                "bar instead."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        }
    }

    // Refuse time-bearing pitch tokens in rhythm-list terms.
    *prRefuseLength { |base, token, whole, label|
        var says = { |what, instead|
            Error(
                "%: % %. A pitch list says nothing about time, which the rhythm "
                "supplies, so %."
                .format(label, this.prLeafAt(token, whole), what, instead)
            ).throw
        };
        if (base.beginsWith("r")) {
            says.value("is a rest",
                "a negative value in that list is where the silence goes")
        };
        if (base.endsWith("~")) {
            says.value("is tied", "a tie comes from that list or from "
                "ScorePrepare")
        };
        if (base.includes($*)) {
            says.value("writes a rational length", "write \"%\" alone".format(
                base.copyRange(0, base.indexOf($*) - 1)))
        };
        if (base.notEmpty and: {
            base.last.isDecDigit or: { base.last == $. }
        }) {
            var bare = this.prWithoutNoteValue(base);
            says.value("ends in a note value", if (bare.isEmpty) {
                "write the pitch alone"
            } {
                "write \"%\" alone".format(bare)
            })
        }
    }

    // drop only the trailing note value. Keep bracketed-octave digits.
    *prWithoutNoteValue { |base|
        var at = base.size;
        while { at > 0 and: { base[at - 1] == $. } } { at = at - 1 };
        while { at > 0 and: { base[at - 1].isDecDigit } } { at = at - 1 };
        if (at < 1) { ^"" };
        ^base.copyRange(0, at - 1)
    }

    // The side a text suffix names, or nil where the name isn't one at all.
    *prTextPlacement { |head|
        if (head == "text") { ^\above };
        ^Marking.placements.detect { |side|
            head == ("text" ++ side.asString.first.toUpper
                ++ side.asString.drop(1)) }
    }

    *prTextHeads {
        ^["text"] ++ Marking.placements.collect { |side|
            "text" ++ side.asString.first.toUpper ++ side.asString.drop(1) }
    }

    *prMarkingNamed { |name, token, whole, label|
        var articulation, level, technical;
        if (name.isEmpty) {
            Error(
                "%: % has an empty marking suffix. Use a dynamic or articulation, e.g. "
                "\"c4:mf:staccato\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        // Ties are read before markings.
        if (name.endsWith("~")) {
            Error(
                "%: % puts a tie after a marking. Write the tie before markings, e.g. "
                "\"c4~:mf\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (name.includes(${)) { ^this.prTextSuffix(name, token, whole, label) };
        // A text suffix written without the prose it needs.
        if (this.prTextPlacement(name).notNil) {
            Error(
                "%: % names text with no braced words. Use \"c4:text{sul pont.}\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (Marking.dynamics.includes(name.asSymbol)) {
            ^Marking.dynamic(name.asSymbol)
        };
        // See Note [A sforzando is an accent at a level] in Marking.sc.
        level = Marking.sforzandoNamed(name);
        if (level.notNil) { ^Marking.sforzando(level) };
        // These spellings name no level here. Compare Strings by value.
        if (["sf", "fz"].any { |each| each == name }) {
            Error(
                "%: \"%\" in % is a sforzando with no level. The family states one: %."
                .format(
                    label,
                    name,
                    this.prLeafAt(token, whole),
                    Marking.sforzandoSuffixes
                )
            ).throw
        };
        // A spelling that names a different mark elsewhere rather
        // than none. MusicXML's `<open/>` is the brass and guitar
        // mute circle, where `<open-string/>` is the string mark, so
        // the model spells the string one out and leaves `open`
        // unclaimed rather than ambiguous.
        if (name == "open") {
            Error(
                "%: \"%\" in % is not the open-string mark. `open` names the mute "
                "circle in MusicXML and the string mark is `openString`, so this "
                "grammar spells it out."
                .format(label, name, this.prLeafAt(token, whole))
            ).throw
        };
        if (name == "rfz") {
            Error(
                "%: \"%\" in % is rinforzando, a reinforcement over time rather than an "
                "attack, so it is not a marking here. Use a hairpin."
                .format(label, name, this.prLeafAt(token, whole))
            ).throw
        };
        // See Note [A spelling is not a vocabulary word] in Marking.sc.
        articulation = Marking.articulationNamed(name);
        if (articulation.notNil) { ^Marking.articulation(articulation) };
        // See Note [A technical mark is not an articulation] in Marking.sc.
        technical = Marking.technicalNamed(name);
        if (technical.notNil) { ^Marking.technical(technical) };
        Error(
            "%: \"%\" in % is not a marking suffix. Dynamics are %, sforzandos are %, "
            "articulations are %, technical marks are %, and text forms are %."
            .format(
                label,
                name,
                this.prLeafAt(token, whole),
                Marking.dynamics,
                Marking.sforzandoSuffixes,
                Marking.articulationSuffixes,
                Marking.technicalSuffixes,
                this.prTextHeads.collect { |head| head ++ "{...}" }.join(", ")
            )
        ).throw
    }

    // `Marking.text` judges the words.
    *prTextSuffix { |name, token, whole, label|
        var open = name.indexOf(${);
        var side = this.prTextPlacement(name.copyRange(0, open - 1));
        var body = this.prBracedBody(name, token, whole, label,
            "c4:text{sul pont.}");
        if (side.isNil) {
            Error(
                "%: \"%\" in % is not a text suffix. Use one of %."
                .format(
                    label,
                    name.copyRange(0, open - 1),
                    this.prLeafAt(token, whole),
                    this.prTextHeads
                )
            ).throw
        };
        ^Marking.text(body, side)
    }

    // A suffix's braced body: prose or leaves. Nothing may follow the close.
    *prBracedBody { |name, token, whole, label, example|
        var open = name.indexOf(${);
        var braces = 0;
        name.copyRange(open, name.size - 1).do { |char, index|
            if (char == ${) { braces = braces + 1 };
            if (char == $}) {
                braces = braces - 1;
                if (braces == 0 and: { (open + index) < (name.size - 1) }) {
                    Error(
                        "%: % has text after the closing brace. Use one braced group, "
                        "e.g. \"%\"."
                        .format(label, this.prLeafAt(token, whole), example)
                    ).throw
                }
            }
        };
        if (name.endsWith("}").not) {
            Error(
                "%: % leaves braces unclosed. Use suffixes like \"%\"."
                .format(label, this.prLeafAt(token, whole), example)
            ).throw
        };
        ^name.copyRange(open + 1, name.size - 2)
    }

    // Catch here to name the written leaf. `Marking` owns the rule
    // and does not know where the marks were written. `where` is a
    // phrase `prLeafAt` shapes, since a chord and a rest already
    // carry one of their own.
    *prMarked { |leaf, markings, where|
        var failure;
        try { markings.do { |mark| leaf.attach(mark) } } { |err| failure = err.what };
        if (failure.notNil) { this.prRefuseMarks(failure, where) };
        ^leaf
    }

    *prRefuseMarks { |failure, where|
        if (where.isNil) { Error(failure).throw };
        Error("% It was written as %.".format(failure, where)).throw
    }

    // `'` raises and `,` lowers from Rastrum octave 4. Bracketed
    // octaves give the absolute form: `c[5]4`.
    //
    // The marks are LilyPond-like. The baseline is Rastrum's.
    // LilyPond output handles its own baseline later.

    // Note [A tie is written after the length]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A trailing `~` ties the whole leaf onward. Inside a chord,
    // `Chord.notation` reads pitch-level ties after this method has
    // split off the tail.


    // Answers [token without tie, tied?].
    *prTieSuffix { |token, whole, label = "Measure.notation"|
        var text = token;
        // Inside `<...>` a `~` binds to the pitch it follows.
        //
        // See Note [A tilde binds to what it follows].
        //
        // `Chord.notation` reads those, so only the length after `>`
        // is this method.
        var close = if (text.beginsWith("<")) { text.find(">") } { nil };
        var head = "";

        close !? {
            head = text.copyRange(0, close);
            text = text.copyToEnd(close + 1);
        };
        if (text.endsWith("~").not) {
            if (text.includes($~)) {
                Error(
                    "%: % writes its tie before the end. Put `~` after the length, e.g. "
                    "\"c2~\"."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            ^[head ++ text, false]
        };
        text = text.copyRange(0, text.size - 2);
        if ((head.isEmpty and: { text.isEmpty }) or: { text.endsWith("~") }) {
            Error(
                "%: % ties more than once. Use one trailing `~`."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        ^[head ++ text, true]
    }

    // Note [A tilde binds to what it follows]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // After a leaf's length it ties the whole leaf. After a pitch
    // inside a chord it ties that pitch alone, which is how a partial
    // mask is written: `<d~ f a~>4` ties the d and the a onward and
    // leaves the f short.
    //
    // The two say the same thing in different scopes, so writing both
    // is refused, not merged. `<d~ f~ a~>4` and `<d f a>4~`
    // are one fact twice over and parse to the same full mask.

    // The tie mask a chord's pitch tokens spell and the tokens
    // without it. Answers [parts, mask], with `mask` nil when no
    // pitch carried a `~`.
    *prChordTieMask { |parts, where, label|
        var found = false;
        var mask = parts.collect { |part| part.endsWith("~") };
        var bare = parts.collect { |part, index|
            if (mask[index].not) { part } {
                var rest = part.copyRange(0, part.size - 2);
                if (rest.endsWith("~")) {
                    Error(
                        "%: % ties % more than once. Use one `~` per pitch."
                        .format(label, where, part)
                    ).throw
                };
                if (rest.isEmpty) {
                    Error(
                        "%: % writes `~` with no pitch under it. A tie in a chord "
                        "follows the pitch it ties, e.g. \"<d~ f a>4\"."
                        .format(label, where)
                    ).throw
                };
                rest
            }
        };
        mask.do { |each| if (each) { found = true } };
        ^[bare, if (found) { mask } { nil }]
    }

    // One pitch spelling, no duration. Shared with `Chord.notation`.
    *prNotationPitch { |text, whole, label = "Measure.notation"|
        var octave = this.prOctaveMarks(text, text, whole, label);
        var head = text.copyRange(0, text.size - 1 - octave[1]);
        if (head.isEmpty) { ^this.prRefuseLeaf(text, whole, label, false) };
        ^MusicPitch(head, octave: octave[0])
    }

    // Answers [octave, marks read].
    *prOctaveMarks { |head, token, whole, label = "Measure.notation"|
        var count = 0, kind, at = head.size;
        while { at > 0 and: {
            (head[at - 1] == RastrumChar.singleQuote) or: { head[at - 1] == Char.comma }
        } } {
            kind = kind ? head[at - 1];
            if (head[at - 1] != kind) {
                Error(
                    "%: % mixes octave-up and octave-down marks. Use only one kind."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            count = count + 1;
            at = at - 1;
        };
        if (kind == $,) { ^[4 - count, count] };
        ^[4 + count, count]
    }

    *prRefuseLeaf { |token, whole, label = "Measure.notation", rests = true|
        Error(
            "%: % is not a leaf. Use pitch plus length, e.g. \"c4\" or \"c*5/8\"%."
            .format(
                label,
                this.prLeafAt(token, whole),
                if (rests) { ", or rest \"r4\"" } { "" }
            )
        ).throw
    }
}
