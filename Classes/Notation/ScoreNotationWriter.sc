// Model values into compact notation that `ScoreNotation` reads back.
// The public `ScoreNotation.format*` methods forward here.
ScoreNotationWriter {

    // Children only. Groups may cover several children.
    // `ignore` holds endpoints an enclosing group already spelled.
    *leafRunString { |children, label, ignore, drop = 0|
        var list = (children ? []).asArray;
        var out = List.new;
        var index = 0;

        if (list.isEmpty) {
            Error("%: there is nothing to format.".format(label)).throw
        };
        while { index < list.size } {
            var group = this.prHairpinGroupAt(list, index, label, ignore, drop)
                ?? { this.prGlissandoGroupAt(list, index, label, ignore, drop) }
                ?? { this.prMarkingGroupAt(list, index, label, ignore, drop) };
            if (group.isNil) {
                out.add(this.prChildString(list[index], label, ignore, drop));
                index = index + 1
            } {
                out.add(group[1]);
                index = index + group[0]
            }
        };
        ^out.join(" ")
    }

    // A parser-spellable hairpin group here, or nil.
    // Boundaries must be leaves. Recursion handles groups inside brackets.
    *prHairpinGroupAt { |list, index, label, ignore, drop = 0|
        var leaves = if (list[index].isKindOf(ScoreLeaf)) {
            list[index].leaves
        } {
            []
        };
        var start = leaves.first !? { |leaf|
            leaf.spanners.detect { |each|
                (each.kind == \hairpin) and: { each.isStart }
                    and: { this.prIgnored(each, ignore).not } }
        };
        var at, stop;

        // Other ids have no group token.
        if (start.isNil or: { start.id != 1 }) { ^nil };
        at = (index .. list.size - 1).detect { |candidate|
            var last = list[candidate].leaves.last;
            last.notNil and: { last.spanners.any { |each|
                (each.kind == \hairpin) and: { each.isStop }
                    and: { each.id == start.id } } }
        };
        if (at.isNil or: { at == index }
            or: { list[at].isKindOf(ScoreLeaf).not }) { ^nil };
        stop = list[at].leaves.last.spanners.detect { |each|
            (each.kind == \hairpin) and: { each.isStop } };
        ^[at - index + 1, "%[%]".format(start.direction,
            this.leafRunString(list.copyRange(index, at), label,
                (ignore ? []) ++ [start, stop], drop))]
    }

    // Parser-spellable `glissando[...]`: one leaf-linked chain.
    // Tied continuations are not new attacks, so they have no group spelling.
    *prGlissandoGroupAt { |list, index, label, ignore, drop = 0|
        var leaves = List.new;
        var at = index;
        var roles;

        while { at < list.size } {
            leaves.addAll(list[at].leaves);
            roles = leaves.asArray.collect { |leaf|
                this.prGlissandoRole(leaf, ignore) };
            if (this.prIsGlissandoChain(roles)) {
                ^[at - index + 1, "glissando[%]".format(
                    this.leafRunString(list.copyRange(index, at), label,
                        (ignore ? []) ++ leaves.asArray.collect { |leaf|
                            leaf.spanners.select { |each| each.isGlissando }
                        }.flatten(1), drop))]
            };
            // A chain still open may reach into the next child. Anything else
            // is not one and will not become one.
            if (this.prIsOpenGlissandoChain(roles).not) { ^nil };
            at = at + 1
        };
        ^nil
    }

    // What one leaf is in a chain, or nil where it is in none. Every endpoint
    // must carry the helper's id. Nothing else may ride the leaf.
    *prGlissandoRole { |leaf, ignore|
        var left = leaf.spanners.reject { |each| this.prIgnored(each, ignore) };
        var starts = left.select { |each|
            each.isGlissando and: { each.isStart } };
        var stops = left.select { |each|
            each.isGlissando and: { each.isStop } };

        if (leaf.isKindOf(MusicRest)) { ^nil };
        if (left.size != (starts.size + stops.size)) { ^nil };
        if (starts.size > 1 or: { stops.size > 1 }) { ^nil };
        if (left.any { |each| each.id != 1 }) { ^nil };
        if (starts.notEmpty and: { stops.notEmpty }) { ^\link };
        if (starts.notEmpty) { ^\start };
        if (stops.notEmpty) { ^\stop };
        ^nil
    }

    // >>> ScoreNotationWriter.prIsGlissandoChain([\start, \link, \stop])   -> true
    // >>> ScoreNotationWriter.prIsGlissandoChain([\start, \link])          -> false
    *prIsGlissandoChain { |roles|
        if (roles.size < 2) { ^false };
        ^(roles.first == \stop).not and: { roles.first == \start }
            and: { roles.last == \stop }
            and: { roles.copyRange(1, roles.size - 2)
                .every { |each| each == \link } }
    }

    // A chain that has not closed, so the next child may still finish it.
    *prIsOpenGlissandoChain { |roles|
        ^(roles.first == \start)
            and: { roles.drop(1).every { |each| each == \link } }
    }

    // Shared marking prefixes, written once as a group head.
    // `drop` counts prefixes already spelled by enclosing heads.
    *prMarkingGroupAt { |list, index, label, ignore, drop = 0|
        this.prGroupHeadCandidates(list[index].leaves.first, drop).do { |prefix|
            var group = this.prMarkingGroupOn(list, index, label, ignore, drop,
                prefix);
            if (group.notNil) { ^group }
        };
        ^nil
    }

    // A group must shorten at least two sibling children.
    *prMarkingGroupOn { |list, index, label, ignore, drop, prefix|
        var at = index;

        while {
            (at < list.size) and: {
                list[at].leaves.every { |leaf|
                    this.prSharesGroupHead(leaf, prefix, drop) }
            }
        } { at = at + 1 };
        at = at - 1;
        if (at <= index) { ^nil };
        ^[at - index + 1, "%[%]".format(
            prefix.collect { |mark| this.prMarkingSuffix(mark, label) }
                .join(":"),
            this.leafRunString(list.copyRange(index, at), label, ignore,
                drop + prefix.size))]
    }

    // Longest first so `sfz:pp` wins, with fallback to `sfz`.
    *prGroupHeadCandidates { |leaf, drop|
        var marks = leaf !? { leaf.markings.drop(drop) };
        var first = marks !? { marks.first };

        if (first.isNil or: { leaf.isKindOf(MusicRest) }) { ^[] };
        if (first.kind == \sforzando) {
            if (marks.size > 1 and: { marks[1].kind == \dynamic }) {
                ^[marks.copyRange(0, 1), [first]]
            };
            ^[[first]]
        };
        if ((first.kind == \articulation) or: { first.kind == \technical }) {
            ^[[first]]
        };
        ^[]
    }

    *prSharesGroupHead { |leaf, prefix, drop|
        var marks = leaf.markings.drop(drop);

        if (leaf.isKindOf(MusicRest) or: { marks.size < prefix.size }) {
            ^false
        };
        ^prefix.every { |mark, index|
            (marks[index].kind == mark.kind)
                and: { marks[index].value == mark.value } }
    }

    // Ignore by live endpoint object, not by value.
    *prIgnored { |endpoint, ignore|
        ^(ignore ? []).any { |each| each === endpoint }
    }

    // One meter-bearing line. A pickup states its meter after `;;`.
    *measureString { |measure, label|
        this.prCheckedMeasure(measure, label);
        ^this.prMeterHead(measure, true, label)
            ++ this.leafRunString(measure.children, label)
    }

    // The first bar states its meter. Later bars carry it until it changes.
    *measureRunString { |measures, label|
        var list = (measures ? []).asArray;
        var meter;

        if (list.isEmpty) {
            Error("%: there are no bars to format.".format(label)).throw
        };
        ^list.collect { |measure, index|
            var head;
            this.prCheckedMeasure(measure, label);
            head = this.prMeterHead(measure,
                (index == 0) or: { measure.meter != meter }, label);
            meter = measure.meter;
            head ++ this.leafRunString(measure.children, label)
        }.join(" | ")
    }

    // `4/4; `, `4/4;; ` for pickups or nothing when carrying a meter
    *prMeterHead { |measure, states, label|
        var meter = measure.meter;
        var text = "%/%%".format(meter.count, meter.unit,
            if (meter.isGrouped) { "[" ++ meter.groups.join("+") ++ "]" } { "" });

        if (measure.isPartial.not) {
            if (states.not) { ^"" };
            ^text ++ "; "
        };
        if (measure.isAnacrusis.not) {
            Error(
                "%: a short bar that is not a pickup has no notation spelling. Write it "
                "as objects, or use Measure.pickup."
                .format(label)
            ).throw
        };
        ^text ++ ";; "
    }

    *prCheckedMeasure { |measure, label|
        if (measure.isKindOf(Measure).not) {
            Error("%: % is not a Measure.".format(label, measure.class)).throw
        };
        if (measure.hasVoices) {
            Error(
                "%: the bar holds voices, which the compact grammar does not read. "
                "Format one voice's children, or write the bar as objects."
                .format(label)
            ).throw
        };
        if (measure.hasDirections) {
            Error(
                "%: the bar holds a direction, which is a bar fact the compact grammar "
                "does not read. Write it as objects."
                .format(label)
            ).throw
        };
        measure.clef !? {
            Error(
                "%: the bar changes clef to %, which the compact grammar does not read. "
                "Write it as objects."
                .format(
                    label,
                    measure.clef
                )
            ).throw
        };
        if (measure.isFull.not) {
            Error(
                "%: the bar declares % and holds %, and the grammar reads no bar that "
                "does not fill what it declares. Write it as objects, or use "
                "Measure.pickup for a short one."
                .format(
                    label,
                    measure.barDuration,
                    measure.voices.first.duration
                )
            ).throw
        };
        ^measure
    }

    *prChildString { |child, label, ignore, drop = 0|
        if (child.isKindOf(Tuplet)) {
            ^this.prTupletString(child, label, ignore, drop)
        };
        if (child.isKindOf(ScoreLeaf)) {
            ^this.prLeafString(child, label, ignore, drop)
        };
        Error(
            "%: a % has no notation spelling. The grammar reads leaves and brackets."
            .format(label, child.class)
        ).throw
    }

    // Stored counts are part of the bracket fact.
    *prTupletString { |tuplet, label, ignore, drop = 0|
        ^"%:%[%]".format(tuplet.actualNotes, tuplet.normalNotes,
            this.leafRunString(tuplet.children, label, ignore, drop))
    }

    // Head, duration, tie then suffixes.
    *prLeafString { |leaf, label, ignore, drop = 0|
        var text;

        this.prCheckedLeaf(leaf, label, ignore);
        text = case
            { leaf.isKindOf(Chord) } { this.prChordString(leaf, label) }
            { leaf.isKindOf(MusicRest) } {
                "r" ++ this.prDurationToken(leaf.duration, label)
            }
            { true } {
                this.prPitchToken(leaf.pitch, label)
                    ++ this.prDurationToken(leaf.duration, label)
                    ++ if (leaf.tiesToNext == true) { "~" } { "" }
            };
        // By count: the same `Marking` instance may appear twice.
        leaf.markings.drop(drop).do { |marking|
            text = text ++ ":" ++ this.prMarkingSuffix(marking, label)
        };
        if (leaf.hasGraces) {
            text = text ++ ":%{%}".format(leaf.graceStyle,
                this.leafRunString(leaf.graces, label))
        };
        ^text
    }

    // A partial tie mask is spelled per pitch.
    *prChordString { |chord, label|
        var mask = chord.tiesToNext.asArray;
        var partial = chord.tiesAll.not and: { mask.any { |each| each } };

        ^"<%>%%".format(
            chord.pitches.collect { |pitch, index|
                this.prPitchToken(pitch, label)
                    ++ if (partial and: { mask[index] == true }) { "~" } { "" }
            }.join(" "),
            this.prDurationToken(chord.duration, label),
            if (chord.tiesAll) { "~" } { "" })
    }

    // Residual cents have no token in the closed accidental grid.
    *prPitchToken { |pitch, label|
        if (pitch.cents != 0) {
            Error(
                "%: % carries % cents, which the compact grammar does not read. Write "
                "it as objects."
                .format(
                    label,
                    pitch.spelling,
                    pitch.cents
                )
            ).throw
        };
        ^pitch.spelling
    }

    // Note values and dots where possible. Starred rationals otherwise.
    //
    // >>> ScoreNotationWriter.prDurationToken(Duration(3, 8))   -> 4.
    // >>> ScoreNotationWriter.prDurationToken(Duration(5, 8))   -> *5/8
    // >>> ScoreNotationWriter.prDurationToken(Duration(2, 1))   -> *2/1
    *prDurationToken { |duration, label|
        var spelled = duration.notation;

        if (duration <= Duration(0, 1)) {
            Error(
                "%: a leaf lasts %, and the grammar reads only a positive duration. "
                "Write it as objects."
                .format(label, duration)
            ).throw
        };
        if (spelled.notNil and: { spelled[0].numerator == 1 }) {
            ^spelled[0].denominator.asString
                ++ String.fill(spelled[1], $.)
        };
        ^"*%/%".format(duration.numerator, duration.denominator)
    }

    // Every marking kind the leaf grammar reads as a suffix.
    *prMarkingSuffix { |marking, label|
        if (marking.kind == \text) { ^this.prTextSuffix(marking, label) };
        if (marking.kind == \sforzando) {
            ^Marking.sforzandoSpelling(marking.value)
        };
        ^marking.value.asString
    }

    // Braces close the suffix. There is no escaping rule yet.
    *prTextSuffix { |marking, label|
        var prose = marking.value.asString;

        if (prose.includes(${) or: { prose.includes($}) }) {
            Error(
                "%: the text %} holds a brace, which closes the suffix. Write it as "
                "objects, or remove the brace."
                .format(
                    label,
                    "\"" ++ prose ++ "\""
                )
            ).throw
        };
        ^"%{%}".format(
            if (marking.placement == \below) { "textBelow" } { "text" }, prose)
    }

    // Anything not already emitted as a group is an unsupported span here.
    *prCheckedLeaf { |leaf, label, ignore|
        var left = leaf.spanners.reject { |each| this.prIgnored(each, ignore) };

        if (left.notEmpty) {
            Error(
                "%: the leaf holds a % endpoint, and the compact grammar writes no such "
                "span here. Detach it, or write the run as objects."
                .format(label, left.first.kind)
            ).throw
        };
        ^leaf
    }
}


+ ScoreNotation {

    // Public formatters. `ScoreNotationWriter` owns spelling and refusals.
    //
    // >>> ScoreNotation.formatLeafRun(ScoreNotation.leafRun("c4~ c4 r8."))
    // c[4]4~ c[4]4 r8.
    *formatLeafRun { |children|
        ^ScoreNotationWriter.leafRunString(children,
            "ScoreNotation.formatLeafRun")
    }

    // >>> ScoreNotation.formatMeasure(Measure("4/4", "c4 d4 e2"))
    // 4/4; c[4]4 d[4]4 e[4]2
    *formatMeasure { |measure|
        ^ScoreNotationWriter.measureString(measure,
            "ScoreNotation.formatMeasure")
    }

    // >>> ScoreNotation.formatMeasureRun(ScoreNotation.measureRun("2/4; c4 d4 | e4 f4"))
    // 2/4; c[4]4 d[4]4 | e[4]4 f[4]4
    *formatMeasureRun { |measures|
        ^ScoreNotationWriter.measureRunString(measures,
            "ScoreNotation.formatMeasureRun")
    }
}
