// Text notation grammar: musical text read as model elements.
//
// Four targets: one leaf, one child run, one measured bar, or a bar
// run. Constructors and quasiquoters delegate here.
//
// This is grammar only: it builds model elements and emits no format.
//
// `leafRun` and `measureRun` name the two targets no constructor
// owns. Leaves and single bars keep their constructor-facing names.
//
// Some `pr` methods are shared with neighbors. The prefix marks
// intent, not privacy.
ScoreNotation {
    // Note [A run of leaves is the one target with no public name]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `leafRun` answers an Array for callers that already know the
    // holder. It may hold leaves, tuplets, and expanded groups. It
    // has no meter, barline or addresses.
    //
    // >>> ScoreNotation.leafRun("c4 r4 <e g>2").collect { |each| each.class }
    // [class MusicNote, class MusicRest, class Chord]
    // >>> ScoreNotation.leafRun("3:2[c8 d8 e8] f4").collect { |each| each.class }
    // [class Tuplet, class MusicNote]
    // >>> ScoreNotation.leafRun("crescendo[c4 d4]").first
    //     .spannerStarts.first.direction   -> crescendo
    // >>> Measure("4/4", ScoreNotation.leafRun("c4 r4 e2")).leaves.size   -> 3
    *leafRun { |text|
        ^this.prNotationChildren(text, "ScoreNotation.leafRun")
    }

    // Note [A run of bars carries its meter forward]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `|` separates bars. A meter persists until another bar states one.
    // Each answered `Measure` still stores its own `Meter`.
    //
    // Bars are not mended here. Short or overfull bars are refused. Pickups,
    // partial bars and preparation are explicit.
    //
    // >>> ScoreNotation.measureRun("4/4; c4 d4 e2 | c4 r4 e2").size   -> 2
    // >>> ScoreNotation.measureRun("4/4; c4 d4 e2 | 3/4; c4 d4 e4 | c4 d4 e4")
    //     .collect { |bar| bar.meter == Meter(3, 4) }
    // [ false, true, true ]
    // >>> Staff(ScoreNotation.measureRun("2/4; c4 d4 | c4 d4"), "V").leaves.size
    // 4
    *measureRun { |text|
        var label = "ScoreNotation.measureRun";
        var meter;
        if (text.isKindOf(String).not) {
            Error("%: expected a bar-run String such as "
                "\"4/4 c4 d4 e2 | c4 r4 e2\", got a %.".format(
                    label, text.class)).throw
        };
        if (text.stripWhiteSpace.isEmpty) {
            Error("%: \"%\" contains no bars. Use \"4/4 c4 d4 e2\", or "
                "\"4/4 c4 d4 e2 | c4 r4 e2\" for several.".format(
                    label, text)).throw
        };
        ^this.prNotationBars(text).collect { |span, at|
            var bar = span.stripWhiteSpace;
            var split;
            if (bar.isEmpty) {
                Error("%: \"%\" has an empty bar. Every `|` separates two bars, "
                    "and Measure.rest(meter) is the bar of silence.".format(label, text)).throw
            };
            split = this.prNotationMeterAndBar(bar);
            if (split.notNil) {
                if (split[0].isEmpty) {
                    Error("%: % has no meter before the semicolon.".format(
                        label, this.prBarAt(bar, at))).throw
                };
                if (split[1].isEmpty) {
                    Error("%: % states a meter and no leaves. Use "
                        "Measure.rest for a silent bar.".format(
                            label, this.prBarAt(bar, at))).throw
                };
                meter = split[0];
                bar = split[1];
            } {
                if (meter.isNil) {
                    Error("%: % states no meter, and there is no bar before it "
                        "to carry one from. Use \"4/4 %\".".format(
                            label, this.prBarAt(bar, at), bar)).throw
                }
            };
            this.prNotation(meter, bar, bar, label, at)
        }
    }

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


    // Note [A meter and a bar, however they are separated]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A line may say `meter; bar` or `meter bar`. A digit/slash head
    // is a meter attempt and goes to `Meter`, even when malformed.


    // Answers [meter, bar], unparsed, or nil when no meter head is
    // present.
    *prNotationMeterAndBar { |line|
        var text = line.stripWhiteSpace;
        var split = this.prNotationSplit(text);
        var head;
        if (split.notNil) { ^split };
        head = this.prFirstToken(text);
        if (this.prIsMeterHead(head).not) { ^nil };
        ^[head, text.drop(head.size).stripWhiteSpace]
    }

    // Loose on purpose: malformed meter-looking heads still reach `Meter`.
    *prIsMeterHead { |token|
        var at = token.indexOf($/);
        if (at.isNil or: { at == 0 }) { ^false };
        ^token.copyRange(0, at - 1).every { |char| char.isDecDigit }
    }

    // A refusal names which bar only when there is more than one to tell apart.
    *prBarAt { |text, at|
        if (at.isNil) { ^"\"%\"".format(text) };
        ^"bar %, \"%\",".format(at + 1, text)
    }

    // Note [A bar written out, and the number is a length]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A written bar is one timeline. The trailing number is a
    // duration; pitch slots use marks or bracketed octaves for
    // register.
    //
    // Tokens cover leaves with suffixes, tuplets, hairpin groups,
    // glissando groups and marking groups. Other cross-leaf facts
    // stay objects.
    //
    // >>> Measure("4/4", "c4 r4 e2").leaves.collect { |x| x.dur }
    // [ Duration(1/4), Duration(1/4), Duration(1/2) ]
    // >>> Measure("4/4", "c4 r4 e2").leaves[1].class   -> MusicRest

    // Note [A written bar may carry its own meter]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The one-String form shares the meter/bar split above.
    // `Measure.new` keeps meter and children separate.
    //
    // A written line must fill its meter. Use `Measure.pickup` or
    // `Measure.partial` for short bars.
    //
    // >>> Measure.notation("4/4; c4 r4 e2").leaves.size   -> 3

    // The meter and the bar of one written line, both stripped, or
    // nil when there is no semicolon to split at.
    *prNotationSplit { |line|
        var at = this.prNotationSemicolon(line);
        if (at.isNil) { ^nil };
        ^[
            if (at > 0) { line.copyRange(0, at - 1).stripWhiteSpace } { "" },
            if (at < (line.size - 1)) {
                line.copyRange(at + 1, line.size - 1).stripWhiteSpace } { "" }
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

    // One written line: a meter, then a bar.
    *prNotationLine { |line|
        var split;
        if (line.isKindOf(String).not) {
            Error("Measure.notation: expected a bar String such as "
                "\"4/4 c4 d4 e2\", got a %.".format(line.class)).throw
        };
        split = this.prNotationMeterAndBar(line);
        if (split.isNil) {
            Error("Measure.notation: \"%\" states no meter. Use "
                "\"4/4 c4 d4 e2\" or Measure.notation(\"4/4\", \"c4 d4 e2\")."
                .format(line)).throw
        };
        if (split[0].isEmpty) {
            Error("Measure.notation: \"%\" has no meter before the semicolon."
                .format(line)).throw
        };
        if (split[1].isEmpty) {
            Error("Measure.notation: \"%\" states a meter and no leaves. "
                "Use Measure.rest for a silent bar.".format(line)).throw
        };
        ^this.prNotation(split[0], split[1], line)
    }

    // Keep refusals in the caller's words.
    *prNotation { |meter, text, whole, label = "Measure.notation", at|
        var bar = Meter.asMeter(meter) ?? { Meter(4, 4) };
        var built = Measure.new(bar, this.prNotationChildren(text, label, true,
            " Measure.rest(meter) is the bar of silence."));
        if (built.isFull.not) {
            Error("%: % comes to %, but a % bar holds %. Use Measure.partial or "
                "Measure.pickup for a short bar.".format(label,
                    this.prBarAt(whole ? text, at),
                    built.voices.first.duration, bar, bar.duration)).throw
        };
        ^built
    }

    // Note [A run of leaves is a run of leaves]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A String may stand anywhere a run of leaves is expected: the
    // whole slot or one fragment in an array.
    //
    // `containers` admits tuplets. Leaf-only slots set it false. A
    // `ScoreSelection` is an already chosen run.
    *prChildrenOf { |children, label, rests = true, containers = true,
        hairpinGroups = true|
        if (children.isKindOf(String)) {
            ^this.prNotationChildren(children, label, rests, "", containers,
                hairpinGroups)
        };
        if (children.isKindOf(ScoreSelection)) { ^children.leaves };
        if (children.isSequenceableCollection and: {
            children.any { |child| child.isKindOf(String) }
        }) {
            ^children.asArray.inject([], { |all, child|
                if (child.isKindOf(String)) {
                    all ++ this.prNotationChildren(child, label, rests, "",
                        containers, hairpinGroups)
                } {
                    all ++ [child]
                }
            })
        };
        ^children
    }

    // A written run of leaves. The holder adds its own rule.
    *prNotationLeaves { |text, label = "Measure.notation", rests = true, hint = ""|
        ^this.prNotationChildren(text, label, rests, hint, false)
    }

    // Note [A bracket is the one container a run admits]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A tuplet is one token whose body is another run. Ties and
    // markings stay on leaves inside it.
    //
    // Leaf-only slots refuse tuplets and hairpin groups. Marking
    // groups expand to leaves. Hairpin bodies set `hairpinGroups`
    // false through brackets.
    *prNotationChildren { |text, label = "Measure.notation", rests = true,
        hint = "", containers = true, hairpinGroups = true|
        var tokens;
        if (text.isKindOf(String).not) {
            Error("%: expected a leaf-run String such as \"c4 d4 e2\", got a %."
                .format(label, text.class)).throw
        };
        tokens = this.prNotationTokens(text, label);
        if (tokens.isEmpty) {
            Error("%: \"%\" contains no leaf tokens. Use spaces, e.g. "
                "\"c4 d4 e2\".%".format(label, text, hint)).throw
        };
        // Hairpin and marking groups answer arrays. Splice one level.
        ^tokens.inject([], { |all, token|
            var child = this.prNotationChild(token.stripWhiteSpace, text, label,
                rests, containers, hairpinGroups);
            if (child.isKindOf(Array)) { all ++ child } { all.add(child) }
        })
    }

    *prNotationChild { |token, whole, label, rests, containers,
        hairpinGroups = true|
        var direction = this.prHairpinHead(token);
        var marking;
        if (direction.notNil) {
            if (containers.not) {
                Error("%: % is a hairpin group, but this slot takes leaves only."
                    .format(label, this.prLeafAt(token, whole))).throw
            };
            // Brackets may sit inside a hairpin. A second hairpin may not.
            if (hairpinGroups.not) {
                Error("%: % is a hairpin group inside one. A hairpin may hold "
                    "a bracket or a marking group between its ends, but not a "
                    "second hairpin.".format(
                        label, this.prLeafAt(token, whole))).throw
            };
            ^this.prNotationHairpin(token, direction, whole, label, rests)
        };
        // See Note [A glissando group is a chain of pairs].
        if (this.prGlissandoHead(token).notNil) {
            if (containers.not) {
                Error("%: % is a glissando group, but this slot takes leaves "
                    "only.".format(label, this.prLeafAt(token, whole))).throw
            };
            ^this.prNotationGlissando(token, whole, label)
        };
        // Before tuplets, but unknown heads stay unreserved.
        marking = this.prMarkingGroupHead(token);
        if (marking.notNil) {
            ^this.prNotationMarkingGroup(token, marking, whole, label, rests,
                containers, hairpinGroups)
        };
        this.prRefuseMarkingGroupHead(token, whole, label);
        if (this.prLooksLikeTupletToken(token).not) {
            // A bare ratio is a bracket typo, not an unknown leaf.
            if (this.prIsRatioToken(token)) {
                Error("%: % is a tuplet ratio with no bracket. Use "
                    "\"3:2[c4 d4 e4]\".".format(
                        label, this.prLeafAt(token, whole))).throw
            };
            // Whole-token match: `crescendo4` stays a leaf attempt.
            if (Spanner.directionNamed(token).notNil) {
                Error("%: % is a hairpin name with no bracket. Use \"%[c4 d4]\"."
                    .format(label, this.prLeafAt(token, whole), token)).throw
            };
            if (Spanner.isGlissandoHead(token)) {
                Error("%: % is a glissando name with no bracket. Use "
                    "\"%[c4 d4]\".".format(
                        label, this.prLeafAt(token, whole), token)).throw
            };
            // Same separated-head typo, for marking groups.
            if (this.prMarkingGroupNamed(token).notNil) {
                Error("%: % is a marking name with no bracket. Use \"%[c4 d4]\"."
                    .format(label, this.prLeafAt(token, whole), token)).throw
            };
            ^this.prNotationLeaf(token, whole, label, rests)
        };
        if (containers.not) {
            Error("%: % is a tuplet, but this slot takes leaves only.".format(
                label, this.prLeafAt(token, whole))).throw
        };
        ^this.prNotationTuplet(token, whole, label, rests, hairpinGroups)
    }

    // Note [A named group is the other bracket]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A hairpin group is `Spanner.crescendo` or `Spanner.diminuendo` written
    // once. The body is spliced into the surrounding run.
    //
    // Heads come from `Spanner.directionHeads`. Unknown heads stay unreserved.
    // Only the first and last children receive endpoints, and both must be
    // leaves. A bracket may stand between them; a second hairpin may not.

    // The direction a hairpin group heads, or nil. See Note [A head is a
    // spelling, a direction is the fact] in Spanner.sc.
    *prHairpinHead { |token|
        var at = this.prOutsideBraces(token, $[);
        if (at.isNil or: { at == 0 }) { ^nil };
        ^Spanner.directionNamed(token.copyRange(0, at - 1))
    }

    // Note [A glissando group is a chain of pairs]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `gliss[...]` is `Spanner.glissando` in notation. It chains pairs:
    // `gliss[c4 e4 d4]` is two lines, not one span.
    //
    // Brackets contribute their leaves, so `gliss[c4 3:2[d8 e8 f8]]`
    // joins the attacks inside the tuplet. Rests are refused here,
    // where the written group can be named.
    //
    // `c4:gliss d4` stays out of this parser. A forward suffix needs
    // a later pass over the built run.

    // The written head of a glissando group, or nil. `Spanner` owns the
    // spellings.
    *prGlissandoHead { |token|
        var at = this.prOutsideBraces(token, $[);
        var head;
        if (at.isNil or: { at == 0 }) { ^nil };
        head = token.copyRange(0, at - 1);
        ^if (Spanner.isGlissandoHead(head)) { head } { nil }
    }

    // `gliss[run]`, parsed as a run with glissando pairs between its attacks.
    // See Note [A glissando group is a chain of pairs].
    *prNotationGlissando { |token, whole, label|
        var at = this.prOutsideBraces(token, $[);
        var written = token.copyRange(0, at - 1);
        var inside, group, leaves;
        if (token.endsWith("]").not) {
            Error("%: % has text after its glissando bracket.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        // Read first, then refuse rests by name.
        group = if (inside.isEmpty) { [] } {
            this.prNotationChildren(inside, label, true, "", true, false) };
        leaves = group.inject([], { |all, child|
            all ++ if (child.isKindOf(ScoreLeaf)) { [child] } { child.leaves } });
        leaves.do { |leaf|
            if (leaf.isKindOf(MusicRest)) {
                Error("%: % holds a rest. A glissando needs pitched attacks."
                    .format(
                        label, this.prLeafAt(token, whole))).throw
            }
        };
        if (leaves.size < 2) {
            Error("%: % needs at least two attacks, got %. Use \"%[c4 d4]\"."
                .format(label, this.prLeafAt(token, whole), leaves.size,
                    written)).throw
        };
        ^Spanner.glissando(group)
    }

    // Note [A marking group is a repeated suffix]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `stac[...]` is the suffix form said once. It creates no model fact.
    //
    // Heads are articulation, technical, plain sforzando, and the one
    // admitted `plainSforzando:dynamic` pair. Dynamics collide with
    // `f[5]4`; text stays leaf prose.
    //
    // Resolved marks attach to every covered leaf, including tuplet
    // leaves. Rests are refused, so every accepted group equals
    // suffix spelling.

    // The marks a group head names, or nil. Always an Array. Shared
    // by bracket parsing and the separated-head refusal.
    //
    // >>> ScoreNotation.prMarkingGroupNamed("stac").first.value    -> staccato
    // >>> ScoreNotation.prMarkingGroupNamed("upbow").first.kind    -> technical
    // >>> ScoreNotation.prMarkingGroupNamed("sfz").first.kind      -> sforzando
    // >>> ScoreNotation.prMarkingGroupNamed("sfz:pp").collect { |m| m.value }
    // [ f, pp ]
    // >>> ScoreNotation.prMarkingGroupNamed("mf")            -> nil
    *prMarkingGroupNamed { |name|
        var value = Marking.articulationNamed(name);
        if (value.notNil) { ^[Marking.articulation(value)] };
        value = Marking.technicalNamed(name);
        if (value.notNil) { ^[Marking.technical(value)] };
        value = Marking.sforzandoNamed(name);
        if (value.notNil) { ^[Marking.sforzando(value)] };
        ^this.prMarkingGroupCompound(name)
    }

    // The `plainSforzando:dynamic` pair, or nil. Check sforzando
    // first so tuplet ratios stay ratios.
    *prMarkingGroupCompound { |name|
        var colon = this.prOutsideBraces(name, $:);
        var level, dynamic;
        if (colon.isNil or: { colon == 0 }) { ^nil };
        level = Marking.sforzandoNamed(name.copyRange(0, colon - 1));
        if (level.isNil) { ^nil };
        dynamic = name.copyRange(colon + 1, name.size - 1);
        if (Marking.dynamics.includes(dynamic.asSymbol).not) { ^nil };
        ^[Marking.sforzando(level), Marking.dynamic(dynamic.asSymbol)]
    }

    // The resolved marks and source spelling, for diagnostics.
    *prMarkingGroupHead { |token|
        var at = this.prOutsideBraces(token, $[);
        var head, marks;
        if (at.isNil or: { at == 0 }) { ^nil };
        head = token.copyRange(0, at - 1);
        marks = this.prMarkingGroupNamed(head);
        ^marks !? { [marks, head] }
    }

    // False friends whose suffix refusals also apply to heads. With a
    // colon, check the first half first so `3:2` stays unclaimed.
    *prRefuseMarkingGroupHead { |token, whole, label|
        var at = this.prOutsideBraces(token, $[);
        var head, colon, first, second;
        if (at.isNil or: { at == 0 }) { ^this };
        head = token.copyRange(0, at - 1);
        colon = this.prOutsideBraces(head, $:);
        first = if (colon.notNil and: { colon > 0 }) {
            head.copyRange(0, colon - 1) } { head };
        // A grace group belongs to its host, so it heads no bracket of its own.
        // See Note [A grace group is a suffix on its host].
        this.prRefuseAppoggiatura(first, token, whole, label);
        if (this.prGraceStyleNamed(first).notNil) {
            Error("%: % heads a grace group with a bracket. Ornaments belong "
                "on a host leaf: \"c4:%{b8}\".".format(
                    label, this.prLeafAt(token, whole), first)).throw
        };
        if (first == "open") {
            Error("%: % is not the open-string mark. `open` names the mute "
                "circle in MusicXML and the string mark is `openString`, so "
                "this grammar spells it out.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        // `includes` on Strings compares identity, so ask by value.
        if (["sf", "fz"].any { |each| each == first }) {
            Error("%: % heads a sforzando with no level. The family states "
                "one: %.".format(label, this.prLeafAt(token, whole),
                    Marking.sforzandoSuffixes)).throw
        };
        if (first == "rfz") {
            Error("%: % heads a rinforzando, a reinforcement over time rather "
                "than an attack. Use a hairpin.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        // Past here a colon is what makes the head a claim about a pair.
        // Without one, an unknown head stays unreserved.
        if (colon.isNil or: { colon == 0 }) { ^this };
        second = head.copyRange(colon + 1, head.size - 1);
        if (Marking.dynamics.includes(first.asSymbol)) {
            Error("%: % heads a dynamic. A dynamic is a level, so it heads no "
                "group. Write the compound attack first, e.g. \"sfz:pp[c4 d4]\"."
                .format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (Marking.sforzandoNamed(first).isNil) { ^this };
        if (second.isEmpty) {
            Error("%: % heads a compound with no level to settle onto. Name "
                "one: %.".format(label, this.prLeafAt(token, whole),
                    Marking.dynamics)).throw
        };
        if (this.prOutsideBraces(second, $:).notNil) {
            Error("%: % heads three marks. A compound head has only attack and "
                "settle level. Put further marks inside the bracket.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        Error("%: \"%\" in % is not a dynamic, so it settles nothing. A "
            "compound head needs one of %.".format(
                label, second, this.prLeafAt(token, whole),
                Marking.dynamics)).throw
    }

    // `head[run]`, parsed as a run, with the resolved marks added to every leaf.
    // See Note [A marking group is a repeated suffix].
    *prNotationMarkingGroup { |token, head, whole, label, rests, containers,
        hairpinGroups|
        var at = this.prOutsideBraces(token, $[);
        var inside, children, leaves;
        if (token.endsWith("]").not) {
            Error("%: % has text after its marking bracket. Put the length and "
                "any further markings inside the bracket.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        if (inside.isEmpty) {
            Error("%: % needs at least one leaf. Use \"%[c4 d4]\".".format(
                label, this.prLeafAt(token, whole), head[1])).throw
        };
        children = this.prNotationChildren(inside, label, rests, "", containers,
            hairpinGroups);
        // Check the same leaves that will receive the mark.
        leaves = children.inject([], { |all, child|
            all ++ if (child.isKindOf(ScoreLeaf)) { [child] } { child.leaves } });
        leaves.do { |leaf|
            if (leaf.isKindOf(MusicRest)) {
                Error("%: % holds a rest. Write \"r4:%\" where the rest should "
                    "carry the mark."
                    .format(label, this.prLeafAt(token, whole), head[1])).throw
            }
        };
        // Written order is outermost first. `attach` appends, so rebuild.
        // Reusing the `Marking` is safe: it has no leaf state.
        leaves.do { |leaf|
            leaf.markings_(head[0] ++ leaf.markings)
        };
        ^children
    }

    // `direction[run]`, parsed as a run with hairpin endpoints
    // attached. See Note [A named group is the other bracket].
    *prNotationHairpin { |token, direction, whole, label, rests|
        var at = this.prOutsideBraces(token, $[);
        var inside, group, written;
        if (token.endsWith("]").not) {
            Error("%: % has text after its hairpin bracket. Put ties and "
                "markings inside the bracket.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        // Keep the spelling for refusals.
        written = token.copyRange(0, at - 1);
        // Parser callers get wording that quotes the written head.
        group = if (inside.isEmpty) { [] } {
            this.prNotationChildren(inside, label, rests, "", true, false) };
        if (group.size < 2) {
            Error("%: % needs at least two leaves, got %. Use \"%[c4 d4]\"."
                .format(label, this.prLeafAt(token, whole), group.size,
                    written)).throw
        };
        // Endpoints must land on leaves.
        [[group.first, "start"], [group.last, "stop"]].do { |end|
            if (end[0].isKindOf(ScoreLeaf).not) {
                Error("%: % has a bracket at its %. A group may hold a bracket "
                    "between its ends, but each end must be a leaf.".format(
                        label, this.prLeafAt(token, whole), end[1])).throw
            }
        };
        // Direction data must have a helper.
        if (Spanner.respondsTo(direction).not) {
            Error("%: % has no % group helper. Use Spanner.hairpinStart(%) and "
                "Spanner.hairpinStop on the two leaves.".format(
                    label, this.prLeafAt(token, whole), direction,
                    direction)).throw
        };
        ^Spanner.perform(direction, group)
    }

    *prLooksLikeTupletToken { |token|
        var at = this.prOutsideBraces(token, $[);
        var head;
        if (at.isNil) { ^false };
        if (at == 0) { ^true };
        head = token.copyRange(0, at - 1);
        ^this.prIsRatioToken(head)
            or: { head.every { |char| char.isDecDigit } }
    }

    // `actual:normal[run]`: counts for `Tuplet.ratio`, body parsed here.
    *prNotationTuplet { |token, whole, label, rests, hairpinGroups = true|
        var at = token.find("[");
        var counts = token.copyRange(0, at - 1);
        if (token.endsWith("]").not) {
            Error("%: % has text after its tuplet bracket. Put ties and markings "
                "inside the bracket.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (counts.isEmpty) {
            Error("%: % is a tuplet bracket with no ratio. Use "
                "\"3:2[c4 d4 e4]\".".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        ^Tuplet.ratio(counts, this.prNotationChildren(
            token.copyRange(at + 1, token.size - 2), label, rests, "", true,
            hairpinGroups))
    }

    // Whitespace separates leaves only at top level. Chords, brackets
    // and text braces keep their own spaces.
    *prNotationTokens { |text, label|
        var tokens = [], current = "", open = false, depth = 0, braces = 0;
        var flush = { if (current.notEmpty) { tokens = tokens.add(current) };
            current = "" };

        text.do { |char|
            case
            // Inside braces, everything is prose.
            { braces > 0 } {
                if (char == ${) { braces = braces + 1 };
                if (char == $}) { braces = braces - 1 };
                current = current ++ char;
            }
            { char == ${ } { braces = braces + 1; current = current ++ char }
            { char == $} } {
                    Error("%: \"%\" closes a brace that never opened. Use text "
                        "suffixes like \"c4:text{sul pont.}\".".format(
                        label, text)).throw
            }
            { char == $< } {
                if (open) {
                    Error("%: \"%\" opens a chord inside a chord.".format(
                        label, text)).throw
                };
                open = true;
                current = current ++ char;
            }
            { char == $> } {
                if (open.not) {
                    Error("%: \"%\" closes a chord that never opened. Use "
                        "<c e g>4.".format(label, text)).throw
                };
                open = false;
                current = current ++ char;
            }
            // Brackets nest; chords do not.
            { char == $[ } { depth = depth + 1; current = current ++ char }
            { char == $] } {
                if (depth == 0) {
                    Error("%: \"%\" closes a bracket that never opened. Use "
                        "3:2[c4 d4 e4].".format(label, text)).throw
                };
                depth = depth - 1;
                current = current ++ char;
            }
            { open.not and: { depth == 0 } and: { char.isSpace } } { flush.value }
            { true } { current = current ++ char };
        };
        if (open) {
            Error("%: \"%\" leaves a chord unclosed. Use <c e g>4.".format(
                label, text)).throw
        };
        if (depth > 0) {
            Error("%: \"%\" leaves a bracket unclosed. Use 3:2[c4 d4 e4]."
                .format(label, text)).throw
        };
        if (braces > 0) {
            Error("%: \"%\" leaves braces unclosed. Use text suffixes like "
                "\"c4:text{sul pont.}\".".format(
                    label, text)).throw
        };
        flush.value;
        ^tokens
    }

    // Pitch spelling, register marks, then length. `rests` is caller policy.
    *prNotationLeaf { |token, whole, label = "Measure.notation", rests = true|
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
                    markings),
                group)
        };
        // Starred lengths use rational duration syntax.
        star = token.indexOf($*);
        if (star.isNil) {
            if (token.includes($/)) {
                Error("%: % writes a rational length without `*`. Use \"%\"."
                    .format(label, this.prLeafAt(token, whole),
                        this.prRationalLeafExample(token, label))).throw
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
                Error("%: % is a rest. Use MusicRest or allow rests in this slot."
                    .format(
                        label, this.prLeafAt(token, whole))).throw
            };
            if (octave[1] > 0) {
                Error("%: % puts an octave mark on a rest.".format(
                    label, this.prLeafAt(token, whole))).throw
            };
            if (tied) {
                Error("%: % ties a rest. Only notes and chords carry ties.".format(
                        label, this.prLeafAt(token, whole))).throw
            };
            ^this.prGraced(this.prMarked(MusicRest(value), markings), group)
        };
        ^this.prGraced(
            this.prMarked(
                MusicNote(MusicPitch(head, octave: octave[0]), value, tied),
                markings),
            group)
    }

    // Note [A star says a rational duration]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `c4` uses note-value grammar. `c*5/8` uses rational duration
    // grammar. The star is the separator, shared with
    // `Chord.notation`.
    *prTailDuration { |tail, token, whole, label = "Measure.notation"|
        var value;
        if (tail.beginsWith("*").not) {
            if (tail.contains("/")) {
                Error("%: % writes a rational length without `*`. Use \"%\"."
                    .format(
                        label, this.prLeafAt(token, whole),
                        this.prRationalLeafExample(token, label))).throw
            };
            ^Duration.lily(tail)
        };
        if (tail.size < 2) {
            Error("%: % has `*` with no rational length. Use \"%\".".format(
                    label, this.prLeafAt(token, whole),
                    this.prRationalLeafExample(token, label))).throw
        };
        if (tail.contains("/").not) {
            Error("%: % has `*` but no slash rational. Use \"%\" or a note "
                "value such as \"c4\".".format(
                    label, this.prLeafAt(token, whole),
                    this.prRationalLeafExample(token, label))).throw
        };
        value = Duration(tail.drop(1));
        // Written leaves have positive duration. Silence is a rest.
        if (value.numerator <= 0) {
            Error("%: % lasts %. A leaf duration must be positive. Use a rest "
                "for silence.".format(
                    label, this.prLeafAt(token, whole), value)).throw
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
    // from `Marking`; text takes braces. Spanners stay object
    // methods.
    //
    // A colon starts suffixes unless the token begins as a tuplet ratio.
    //
    // Note [A grace group is a suffix on its host]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `c4:grace{b8 a8}` is one suffix on its host. The head names the
    // style, `grace` or `acciaccatura`; the body is a leaf run
    // attached through `ScoreLeaf.grace`.
    //
    // The model stores the group on the host, so there is no
    // forward-binding `grace[b8] c4` form.
    //
    // Answers [the token without suffixes, the markings in written order, and
    // [grace leaves, style] or nil].
    *prLeafSuffixes { |token, whole, label = "Measure.notation", graces = true|
        var at, base, markings = [], group;
        // Chords and rests build themselves, so braces are checked here too.
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
                    Error("%: % writes two grace groups. A leaf has one, so put "
                        "every grace leaf in one body.".format(
                            label, this.prLeafAt(token, whole))).throw
                };
                group = [this.prGraceBody(name, token, whole, label), style]
            }
        };
        ^[base, markings, group]
    }

    // The style a grace suffix names, or nil where the name is no
    // grace suffix. A head that names one and then says nothing
    // usable is refused here. See Note [A grace group is a suffix on
    // its host].
    *prGraceSuffixStyle { |name, token, whole, label, graces = true|
        var open = name.indexOf(${);
        var head = if (open.isNil) { name } { name.copyRange(0, open - 1) };
        var style = this.prGraceStyleNamed(head);
        if (style.isNil) {
            this.prRefuseAppoggiatura(head, token, whole, label);
            ^nil
        };
        if (graces.not) {
            Error("%: % carries a grace group. A pitch list names attacks only. "
                "Write the ornament in a bar instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (open.isNil) {
            Error("%: % names a grace group with no braced leaves. Use "
                "\"c4:%{b8}\".".format(
                    label, this.prLeafAt(token, whole), head)).throw
        };
        ^style
    }

    // The grace style a head spells, or nil. `ScoreLeaf` owns the vocabulary.
    //
    // >>> ScoreNotation.prGraceStyleNamed("acciaccatura")   -> acciaccatura
    // >>> ScoreNotation.prGraceStyleNamed("mordent")        -> nil
    *prGraceStyleNamed { |head|
        ^ScoreLeaf.graceStyles.detect { |each| head == each.asString }
    }

    // Refuse appoggiatura by name rather than as an unknown suffix.
    *prRefuseAppoggiatura { |head, token, whole, label|
        if (head != "appoggiatura") { ^this };
        Error("%: % writes an appoggiatura, which the model does not carry. Use "
            "\"c4:grace{b8}\" or \"c4:acciaccatura{b8}\".".format(
                label, this.prLeafAt(token, whole))).throw
    }

    // The leaves a grace body holds, read as the leaf run it is.
    *prGraceBody { |name, token, whole, label|
        var body = this.prBracedBody(name, token, whole, label, "c4:grace{b8}");
        if (body.stripWhiteSpace.isEmpty) {
            Error("%: % has an empty grace group. Use \"c4:grace{b8}\".".format(
                label, this.prLeafAt(token, whole))).throw
        };
        ^this.prNotationLeaves(body, label, false)
    }

    // A parsed group reaches its host through the model's own entry point, so
    // the style is set where `ScoreLeaf.grace` sets it.
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
    //
    // Answers `pitch -> markings`, avoiding two-element Array pitch specs.
    *prMarkedPitches { |text, label|
        var tokens = this.prNotationTokens(text, label);
        if (tokens.isEmpty) {
            Error("%: \"%\" contains no pitch tokens. Use spaces, e.g. "
                "\"c e g\".".format(label, text)).throw
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
            Error("%: % is a chord. A pitch list names one pitch per attack, "
                "so a chord is written out in a bar instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (this.prLooksLikeTupletToken(token)) {
            Error("%: % is a tuplet bracket. A pitch list names one pitch per "
                "attack and the rhythm list says the durations, so a bracket "
                "is written out in a bar instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (this.prHairpinHead(token).notNil) {
            Error("%: % is a hairpin group. A pitch list cycles and a repeated "
                "endpoint says nothing, so a span is written out in a bar or "
                "attached with Spanner instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (this.prGlissandoHead(token).notNil) {
            Error("%: % is a glissando group. A pitch list cycles, so write "
                "the run as a bar or attach it with Spanner instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        // Name marking groups before pitch parsing.
        if (this.prMarkingGroupHead(token).notNil) {
            Error("%: % is a marking group. A pitch list cycles. Write the "
                "mark as suffixes, e.g. \"c:stac d:stac\", or write the run "
                "as a bar.".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        // A bare ratio is a bracket typo here too.
        if (this.prIsRatioToken(token)) {
            Error("%: % is a tuplet ratio. A pitch list names one pitch per "
                "attack and the rhythm list says the durations, so a bracket "
                "is written out in a bar instead.".format(
                    label, this.prLeafAt(token, whole))).throw
        }
    }

    // Refuse time-bearing pitch tokens in rhythm-list terms.
    *prRefuseLength { |base, token, whole, label|
        var says = { |what, instead|
            Error("%: % %. A pitch list says nothing about time, which the "
                "rhythm supplies, so %.".format(
                    label, this.prLeafAt(token, whole), what, instead)).throw
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

    // Drop only the trailing note value. Keep bracketed-octave digits.
    *prWithoutNoteValue { |base|
        var at = base.size;
        while { at > 0 and: { base[at - 1] == $. } } { at = at - 1 };
        while { at > 0 and: { base[at - 1].isDecDigit } } { at = at - 1 };
        if (at < 1) { ^"" };
        ^base.copyRange(0, at - 1)
    }

    // Note [Prose is written in braces]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Braces let prose contain spaces, colons and brackets. `text{...}` is the
    // default side; `textAbove` and `textBelow` name one.
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
                    Error("%: % closes a brace that never opened. Use text "
                        "suffixes like \"c4:text{sul pont.}\".".format(
                            label, this.prLeafAt(token, whole))).throw
                }
            }
        };
        if (braces > 0) {
            Error("%: % leaves braces unclosed. Use text suffixes like "
                "\"c4:text{sul pont.}\".".format(
                    label, this.prLeafAt(token, whole))).throw
        }
    }

    *prRefuseStrayBraces { |part, token, whole, label|
        if (part.includes(${) or: { part.includes($}) }) {
            Error("%: % has braces without a text suffix. Use "
                "\"c4:text{sul pont.}\".".format(
                    label, this.prLeafAt(token, whole))).throw
        }
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
            Error("%: % has an empty marking suffix. Use a dynamic or "
                "articulation, e.g. \"c4:mf:staccato\".".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        // Ties are read before markings.
        if (name.endsWith("~")) {
            Error("%: % puts a tie after a marking. Write the tie before "
                "markings, e.g. \"c4~:mf\".".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (name.includes(${)) { ^this.prTextSuffix(name, token, whole, label) };
        // A text suffix written without the prose it needs.
        if (this.prTextPlacement(name).notNil) {
            Error("%: % names text with no braced words. Use "
                "\"c4:text{sul pont.}\".".format(
                    label, this.prLeafAt(token, whole))).throw
        };
        if (Marking.dynamics.includes(name.asSymbol)) {
            ^Marking.dynamic(name.asSymbol)
        };
        // See Note [A sforzando is an accent at a level] in Marking.sc.
        level = Marking.sforzandoNamed(name);
        if (level.notNil) { ^Marking.sforzando(level) };
        // These spellings name no level here. Compare Strings by value.
        if (["sf", "fz"].any { |each| each == name }) {
            Error("%: \"%\" in % is a sforzando with no level. The family "
                "states one: %.".format(label, name, this.prLeafAt(token, whole),
                    Marking.sforzandoSuffixes)).throw
        };
        // A spelling that names a different mark elsewhere rather
        // than none. MusicXML's `<open/>` is the brass and guitar
        // mute circle, where `<open-string/>` is the string mark, so
        // the model spells the string one out and leaves `open`
        // unclaimed rather than ambiguous.
        if (name == "open") {
            Error("%: \"%\" in % is not the open-string mark. `open` names the "
                "mute circle in MusicXML and the string mark is `openString`, "
                "so this grammar spells it out.".format(
                    label, name, this.prLeafAt(token, whole))).throw
        };
        if (name == "rfz") {
            Error("%: \"%\" in % is rinforzando, a reinforcement over time "
                "rather than an attack, so it is not a marking here. Use a "
                "hairpin.".format(label, name, this.prLeafAt(token, whole))).throw
        };
        // See Note [A spelling is not a vocabulary word] in Marking.sc.
        articulation = Marking.articulationNamed(name);
        if (articulation.notNil) { ^Marking.articulation(articulation) };
        // See Note [A technical mark is not an articulation] in Marking.sc.
        technical = Marking.technicalNamed(name);
        if (technical.notNil) { ^Marking.technical(technical) };
        Error("%: \"%\" in % is not a marking suffix. Dynamics are %, "
            "sforzandos are %, articulations are %, technical marks are %, and "
            "text forms are %."
            .format(
                label, name, this.prLeafAt(token, whole), Marking.dynamics,
                Marking.sforzandoSuffixes, Marking.articulationSuffixes,
                Marking.technicalSuffixes,
                this.prTextHeads.collect { |head| head ++ "{...}" }.join(", "))
            ).throw
    }

    // `Marking.text` judges the words.
    *prTextSuffix { |name, token, whole, label|
        var open = name.indexOf(${);
        var side = this.prTextPlacement(name.copyRange(0, open - 1));
        var body = this.prBracedBody(name, token, whole, label,
            "c4:text{sul pont.}");
        if (side.isNil) {
            Error("%: \"%\" in % is not a text suffix. Use one of %.".format(label,
                    name.copyRange(0, open - 1), this.prLeafAt(token, whole),
                    this.prTextHeads)).throw
        };
        ^Marking.text(body, side)
    }

    // A suffix's braced body, prose or leaves. Nothing may follow the close.
    *prBracedBody { |name, token, whole, label, example|
        var open = name.indexOf(${);
        var braces = 0;
        name.copyRange(open, name.size - 1).do { |char, index|
            if (char == ${) { braces = braces + 1 };
            if (char == $}) {
                braces = braces - 1;
                if (braces == 0 and: { (open + index) < (name.size - 1) }) {
                    Error("%: % has text after the closing brace. Use one "
                        "braced group, e.g. \"%\".".format(
                            label, this.prLeafAt(token, whole), example)).throw
                }
            }
        };
        if (name.endsWith("}").not) {
            Error("%: % leaves braces unclosed. Use suffixes like \"%\".".format(
                label, this.prLeafAt(token, whole), example)).throw
        };
        ^name.copyRange(open + 1, name.size - 2)
    }

    *prMarked { |leaf, markings|
        markings.do { |mark| leaf.attach(mark) };
        ^leaf
    }

    *prIsRatioToken { |token|
        var at = this.prOutsideBraces(token, $:);
        if (at.isNil or: { at == 0 }) { ^false };
        ^token.copyRange(0, at - 1).every { |char| char.isDecDigit }
    }

    // A refusal names the bar only when there is one.
    *prLeafAt { |token, whole|
        if (token == whole) { ^"\"%\"".format(token) };
        ^"\"%\" in \"%\"".format(token, whole)
    }

    // Note [Register marks in a leaf token]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `'` raises and `,` lowers from Rastrum octave 4. Bracketed
    // octaves give the absolute form: `c[5]4`.
    //
    // The marks are LilyPond-like, but the baseline is Rastrum's.
    // LilyPond output handles its own baseline later.
    //
    // Note [A tie is written after the length]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A trailing `~` ties the whole leaf onward. Inside a chord,
    // `Chord.notation` reads pitch-level ties after this method has
    // split off the tail.
    //
    // Answers [token without tie, tied?].
    *prTieSuffix { |token, whole, label = "Measure.notation"|
        var text = token;
        // Inside `<...>` a `~` binds to the pitch it follows, by
        // Note [A tilde binds to what it follows]. `Chord.notation` reads
        // those, so only the length after `>` is this method's.
        var close = if (text.beginsWith("<")) { text.find(">") } { nil };
        var head = "";

        close !? {
            head = text.copyRange(0, close);
            text = text.copyToEnd(close + 1);
        };
        if (text.endsWith("~").not) {
            if (text.includes($~)) {
                Error("%: % writes its tie before the end. Put `~` after the "
                    "length, e.g. \"c2~\".".format(
                        label, this.prLeafAt(token, whole))).throw
            };
            ^[head ++ text, false]
        };
        text = text.copyRange(0, text.size - 2);
        if ((head.isEmpty and: { text.isEmpty }) or: { text.endsWith("~") }) {
            Error("%: % ties more than once. Use one trailing `~`.".format(
                label, this.prLeafAt(token, whole))).throw
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
    // is refused rather than merged. `<d~ f~ a~>4` and `<d f a>4~`
    // are one fact twice over and parse to the same full mask.

    // The tie mask a chord's pitch tokens spell, and the tokens
    // without it. Answers [parts, mask], with `mask` nil when no
    // pitch carried a `~`.
    *prChordTieMask { |parts, where, label|
        var found = false;
        var mask = parts.collect { |part| part.endsWith("~") };
        var bare = parts.collect { |part, index|
            if (mask[index].not) { part } {
                var rest = part.copyRange(0, part.size - 2);
                if (rest.endsWith("~")) {
                    Error("%: % ties % more than once. Use one `~` per pitch."
                        .format(label, where, part)).throw
                };
                if (rest.isEmpty) {
                    Error("%: % writes `~` with no pitch under it. A tie in a "
                        "chord follows the pitch it ties, e.g. \"<d~ f a>4\"."
                        .format(label, where)).throw
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
                Error("%: % mixes octave-up and octave-down marks. Use only one "
                    "kind.".format(label, this.prLeafAt(token, whole))).throw
            };
            count = count + 1;
            at = at - 1;
        };
        if (kind == $,) { ^[4 - count, count] };
        ^[4 + count, count]
    }

    *prRefuseLeaf { |token, whole, label = "Measure.notation", rests = true|
        Error("%: % is not a leaf. Use pitch plus length, e.g. \"c4\" or "
            "\"c*5/8\"%.".format(
                label, this.prLeafAt(token, whole),
                if (rests) { ", or rest \"r4\"" } { "" })).throw
    }
}
