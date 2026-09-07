// Text notation grammar: musical text read as model elements.
//
// Four targets: one leaf, one child run, one measured bar or a bar
// run. Constructors and quasiquoters delegate here.
//
// This is grammar only: it builds model elements and emits no format.
//
// `leafRun` and `measureRun` name the two targets no constructor
// owns. Leaves and single bars keep their constructor-facing names.
ScoreNotation {
    // `leafRun` answers an Array for callers that already know the
    // holder. It may hold leaves, tuplets and expanded groups. It has
    // no meter, barline or addresses.
    //
    // >>> ScoreNotation.leafRun("c4 r4 <e g>2").collect { |each| each.class }
    // [class MusicNote, class MusicRest, class Chord]
    // >>> ScoreNotation.leafRun("3:2[c8 d8 e8] f4").collect { |each| each.class }
    // [class Tuplet, class MusicNote]
    // >>> ScoreNotation.leafRun("crescendo[c4 d4]").first.spannerStarts.first.direction   -> crescendo
    // >>> Measure("4/4", ScoreNotation.leafRun("c4 r4 e2")).leaves.size   -> 3
    *leafRun { |text|
        ^this.prNotationChildren(text, "ScoreNotation.leafRun")
    }

    // `|` separates bars. A meter persists until another bar states one.
    // Each answered `Measure` still stores its own `Meter`.
    //
    // Bars are not mended here. Short or overfull bars are refused. A
    // pickup uses `;;` after its stated meter at whatever bar it is
    // written. Other partial bars and preparation stay explicit
    // object forms.
    //
    // >>> ScoreNotation.measureRun("4/4; c4 d4 e2 | c4 r4 e2").size   -> 2
    // >>> ScoreNotation.measureRun("4/4; c4 d4 e2 | 3/4; c4 d4 e4 | c4 d4 e4").collect { |bar| bar.meter == Meter(3, 4) }
    // [ false, true, true ]
    // >>> Staff(ScoreNotation.measureRun("2/4; c4 d4 | c4 d4"), "V").leaves.size
    // 4
    // >>> ScoreNotation.measureRun("2/4;; c8 d8 | c4 d4").first.isAnacrusis
    // true
    // >>> ScoreNotation.measureRun("2/4; c4 d4 | 2/4;; c8 d8").collect { |bar| bar.isAnacrusis }
    // [ false, true ]
    *measureRun { |text|
        var label = "ScoreNotation.measureRun";
        var meter;
        if (text.isKindOf(String).not) {
            Error(
                "%: expected a bar-run String such as \"4/4 c4 d4 e2 | c4 r4 e2\", got a %.".format(label, text.class)
            ).throw
        };
        if (text.stripWhiteSpace.isEmpty) {
            Error(
                "%: \"%\" contains no bars. Use \"4/4 c4 d4 e2\", or \"4/4 c4 d4 e2 | c4 r4 e2\" for several.".format(label, text)
            ).throw
        };
        ^this.prNotationBars(text).collect { |span, at|
            var bar = span.stripWhiteSpace;
            var source = bar;
            var split;
            var pickup = false;
            if (bar.isEmpty) {
                Error(
                    "%: \"%\" has an empty bar. Every `|` separates two bars, and Measure.rest(meter) is the bar of silence.".format(label, text)
                ).throw
            };
            split = this.prNotationMeterAndBar(bar);
            if (split.notNil) {
                if (split[0].isEmpty) {
                    Error("%: % has no meter before the semicolon.".format(label, this.prBarAt(bar, at))).throw
                };
                if (split[1].isEmpty) {
                    Error(
                        "%: % states a meter and no leaves. Use Measure.rest for a silent bar.".format(label, this.prBarAt(bar, at))
                    ).throw
                };
                meter = split[0];
                bar = split[1];
                pickup = split[2] ? false;
            } {
                if (meter.isNil) {
                    Error(
                        "%: % states no meter, and there is no bar before it to carry one from. Use \"4/4 %\".".format(label, this.prBarAt(bar, at), bar)
                    ).throw
                }
            };
            if (pickup) {
                this.prNotationPickup(meter, bar, source, label, at)
            } {
                this.prNotation(meter, bar, bar, label, at)
            }
        }
    }


    // Note [A written bar is a meter and a strict body]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A line may say `meter; bar` or `meter bar`. Two syntax options.
    //
    // A double semicolon `meter;; bar` is different. It marks that
    // line as a pickup. A digit/slash head is a meter attempt and
    // goes to `Meter` even when malformed.
    //
    // A written bar is one timeline. The trailing number is a
    // duration. Pitch slots use marks or bracketed octaves for
    // register. Tokens cover leaves with suffixes, tuplets, hairpin
    // groups, glissando groups and marking groups. Other cross-leaf
    // facts stay objects.
    //
    // The one-String `Measure.notation` form shares this split with
    // `measureRun`. A written line must fill its meter unless it uses
    // `;;` to say it is a pickup.
    //
    // Other partial bars use `Measure.partial`.
    //
    // >>> Measure("4/4", "c4 r4 e2").leaves.collect { |x| x.dur }
    // [ Duration(1/4), Duration(1/4), Duration(1/2) ]
    // >>> Measure("4/4", "c4 r4 e2").leaves[1].class   -> MusicRest
    // >>> Measure.notation("4/4; c4 r4 e2").leaves.size   -> 3
    // >>> Measure.notation("2/4;; c8 d8").isAnacrusis   -> true
    //
    // Answers [meter, bar, pickup], unparsed or nil when no meter
    // head is present.
    *prNotationMeterAndBar { |line|
        var text = line.stripWhiteSpace;
        var split = this.prNotationSplit(text);
        var head;
        if (split.notNil) { ^split };
        head = this.prFirstToken(text);
        if (this.prIsMeterHead(head).not) { ^nil };
        ^[head, text.drop(head.size).stripWhiteSpace, false]
    }

    // Loose on purpose: malformed meter-looking heads still reach `Meter`.
    *prIsMeterHead { |token|
        var at = token.indexOf($/);
        if (at.isNil or: { at == 0 }) { ^false };
        ^token.copyRange(0, at - 1).every { |char| char.isDecDigit }
    }

    // One written line: a meter then a bar.
    // See Note [A written bar is a meter and a strict body].
    *prNotationLine { |line|
        var split;
        if (line.isKindOf(String).not) {
            Error(
                "Measure.notation: expected a bar String such as \"4/4 c4 d4 e2\", got a %.".format(line.class)
            ).throw
        };
        split = this.prNotationMeterAndBar(line);
        if (split.isNil) {
            Error(
                "Measure.notation: \"%\" states no meter. Use \"4/4 c4 d4 e2\" or Measure.notation(\"4/4\", \"c4 d4 e2\").".format(line)
            ).throw
        };
        if (split[0].isEmpty) {
            Error(
                "Measure.notation: \"%\" has no meter before the semicolon.".format(line)
            ).throw
        };
        if (split[1].isEmpty) {
            Error(
                "Measure.notation: \"%\" states a meter and no leaves. Use Measure.rest for a silent bar.".format(line)
            ).throw
        };
        if (split[2] ? false) {
            ^this.prNotationPickup(split[0], split[1], line)
        };
        ^this.prNotation(split[0], split[1], line)
    }

    // Keep refusals in the caller's words.
    *prNotation { |meter, text, whole, label = "Measure.notation", at|
        var bar = Meter.asMeter(meter) ?? { Meter(4, 4) };
        var built = Measure.new(bar, this.prNotationChildren(text, label, true,
            " Measure.rest(meter) is the bar of silence."));
        if (built.isFull.not) {
            Error(
                "%: % comes to %, but a % bar holds %. Use Measure.partial or Measure.pickup for a short bar."
                .format(
                    label,
                    this.prBarAt(whole ? text, at),
                    built.voices.first.duration,
                    bar,
                    bar.duration
                )
            ).throw
        };
        ^built
    }

    // A pickup is short by assertion, not by inference. Its written
    // body names the span. The meter decides where that span sits.
    *prNotationPickup { |meter, text, whole, label = "Measure.notation", at|
        var bar = Meter.asMeter(meter) ?? { Meter(4, 4) };
        var children = this.prNotationChildren(text, label, true,
            " Measure.rest(meter) is the bar of silence.");
        var span = ScoreContainer(children).duration;
        if (span >= bar.duration) {
            Error(
                "%: % uses `;;`, but its duration is %. A pickup in % must be shorter "
                "than %. Use one `;` or a space for a full bar."
                .format(
                    label,
                    this.prBarAt(whole ? text, at),
                    span,
                    bar,
                    bar.duration
                )
            ).throw
        };
        ^Measure.pickup(bar, children, span)
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
            Error(
                "%: expected a leaf-run String such as \"c4 d4 e2\", got a %."
                .format(label, text.class)
            ).throw
        };
        tokens = this.prNotationTokens(text, label);
        if (tokens.isEmpty) {
            Error(
                "%: \"%\" contains no leaf tokens. Use spaces, e.g. \"c4 d4 e2\".%"
                .format(label, text, hint)
            ).throw
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
                Error(
                    "%: % is a hairpin group, but this slot takes leaves only.".format(label, this.prLeafAt(token, whole))
                ).throw
            };
            // Brackets may sit inside a hairpin. A second hairpin may not.
            if (hairpinGroups.not) {
                Error(
                    "%: % is a hairpin group inside one. A hairpin may hold a bracket "
                    "or a marking group between its ends, but not a second hairpin."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            };
            ^this.prNotationHairpin(token, direction, whole, label, rests)
        };
        // See Note [A glissando group is a chain of pairs] in ScoreNotationGroups.sc.
        if (this.prGlissandoHead(token).notNil) {
            if (containers.not) {
                Error(
                    "%: % is a glissando group, but this slot takes leaves only.".format(label, this.prLeafAt(token, whole))
                ).throw
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
                Error(
                    "%: % is a tuplet ratio with no bracket. Use \"3:2[c4 d4 e4]\".".format(label, this.prLeafAt(token, whole))
                ).throw
            };
            // Whole-token match: `crescendo4` stays a leaf attempt.
            if (Spanner.directionNamed(token).notNil) {
                Error(
                    "%: % is a hairpin name with no bracket. Use \"%[c4 d4]\".".format(label, this.prLeafAt(token, whole), token)
                ).throw
            };
            if (Spanner.isGlissandoHead(token)) {
                Error(
                    "%: % is a glissando name with no bracket. Use \"%[c4 d4]\".".format(label, this.prLeafAt(token, whole), token)
                ).throw
            };
            // Same separated-head typo for marking groups.
            if (this.prMarkingGroupNamed(token).notNil) {
                Error(
                    "%: % is a marking name with no bracket. Use \"%[c4 d4]\".".format(label, this.prLeafAt(token, whole), token)
                ).throw
            };
            ^this.prNotationLeaf(token, whole, label, rests)
        };
        if (containers.not) {
            Error(
                "%: % is a tuplet, but this slot takes leaves only.".format(label, this.prLeafAt(token, whole))
            ).throw
        };
        ^this.prNotationTuplet(token, whole, label, rests, hairpinGroups)
    }
}
