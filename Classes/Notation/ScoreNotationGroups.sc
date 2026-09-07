// Group parsing for `ScoreNotation`.
//
// The bracket forms: tuplets, hairpins, glissandi and marking groups.
// Each answers several children or attaches one fact to several leaves.
// A leaf token answers one. `prNotationChild` decides which is which
// and stays with the grammar.

+ ScoreNotation {

    // Note [A named group is the other bracket]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A hairpin group is `Spanner.crescendo` or
    // `Spanner.diminuendo` written once. The body is spliced into
    // the surrounding run.
    //
    // Heads come from `Spanner.directionHeads`. Unknown heads stay unreserved.
    // Only the first and last children receive endpoints. Both must be
    // leaves. A bracket may stand between them. A second hairpin may not.

    // The direction named by a hairpin group head, or nil.
    // See Note [A head is a spelling, a direction is the fact] in Spanner.sc.
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
    *prNotationGlissando { |token, whole, label|
        var at = this.prOutsideBraces(token, $[);
        var written = token.copyRange(0, at - 1);
        var inside, group, leaves;
        if (token.endsWith("]").not) {
            Error(
                "%: % has text after its glissando bracket."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        // Read first, then refuse rests by name.
        group = if (inside.isEmpty) { [] } {
            this.prNotationChildren(inside, label, true, "", true, false) };
        leaves = group.inject([], { |all, child|
            all ++ if (child.isKindOf(ScoreLeaf)) { [child] } { child.leaves } });
        leaves.do { |leaf|
            if (leaf.isKindOf(MusicRest)) {
                Error(
                    "%: % holds a rest. A glissando needs pitched attacks."
                    .format(label, this.prLeafAt(token, whole))
                ).throw
            }
        };
        if (leaves.size < 2) {
            Error(
                "%: % needs at least two attacks, got %. Use \"%[c4 d4]\"."
                .format(
                    label,
                    this.prLeafAt(token, whole),
                    leaves.size,
                    written
                )
            ).throw
        };
        ^Spanner.glissando(group)
    }

    // Note [A marking group is a repeated suffix]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `stac[...]` is the suffix form said once. It creates no model fact.
    //
    // Heads are articulation, technical, plain sforzando and the one
    // admitted `plainSforzando:dynamic` pair. Dynamics collide with
    // `f[5]4`. Text stays leaf prose.
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

    // The resolved marks and source spelling for diagnostics.
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
        var issue = this.prMarkingGroupHeadIssue(token, whole, label);
        if (issue.isNil) { ^this };
        Error(issue[\message]).throw
    }

    // The one refusal a bracket head earns, or nil.
    //
    // Order matters: `3:2` stays a ratio and appoggiatura keeps its
    // own refusal.
    *prMarkingGroupHeadIssue { |token, whole, label|
        var at = this.prOutsideBraces(token, $[);
        var head, colon, first, second, where;
        if (at.isNil or: { at == 0 }) { ^nil };
        head = token.copyRange(0, at - 1);
        colon = this.prOutsideBraces(head, $:);
        first = if (colon.notNil and: { colon > 0 }) {
            head.copyRange(0, colon - 1) } { head };
        where = this.prLeafAt(token, whole);
        // A grace group belongs to its host, so it heads no bracket of its own.
        // See Note [A grace group is a suffix on its host] in ScoreNotationLeaves.sc.
        this.prAppoggiaturaIssue(first, token, whole, label) !? { |issue|
            ^issue };
        if (this.prGraceStyleNamed(first).notNil) {
            ^ScoreIssue.prOf(\markingGroupGraceHeadOutOfPlace,
                "%: % heads a grace group. Put ornaments on a host leaf: "
                "\"c4:%{b8}\".".format(label, where, first))
        };
        if (first == "open") {
            ^ScoreIssue.prOf(\markingGroupOpenReserved,
                "%: % is not the open-string mark. `open` names the "
                "MusicXML mute circle; use `openString`.".format(label, where))
        };
        // `includes` on Strings compares identity, so ask by value.
        if (["sf", "fz"].any { |each| each == first }) {
            ^ScoreIssue.prOf(\markingGroupSforzandoMissingLevel,
                "%: % heads a sforzando with no level. Use one of %."
                    .format(label, where, Marking.sforzandoSuffixes))
        };
        if (first == "rfz") {
            ^ScoreIssue.prOf(\markingGroupRinforzandoOutOfPlace,
                "%: % heads a rinforzando. Use a hairpin for reinforcement "
                "over time.".format(label, where))
        };
        // Past here a colon makes the head a claim about a pair.
        // Without one an unknown head stays unreserved.
        if (colon.isNil or: { colon == 0 }) { ^nil };
        second = head.copyRange(colon + 1, head.size - 1);
        if (Marking.dynamics.includes(first.asSymbol)) {
            ^ScoreIssue.prOf(\markingGroupDynamicOutOfPlace,
                "%: % heads a dynamic. Write the attack first, e.g. "
                "\"sfz:pp[c4 d4]\".".format(label, where))
        };
        if (Marking.sforzandoNamed(first).isNil) { ^nil };
        if (second.isEmpty) {
            ^ScoreIssue.prOf(\markingGroupCompoundMissingDynamic,
                "%: % heads a compound with no dynamic. Use one of %."
                    .format(label, where, Marking.dynamics))
        };
        if (this.prOutsideBraces(second, $:).notNil) {
            ^ScoreIssue.prOf(\markingGroupCompoundTooManyParts,
                "%: % heads three marks. A compound head has attack and "
                "dynamic only.".format(label, where))
        };
        ^ScoreIssue.prOf(\markingGroupCompoundSettleNotADynamic,
            "%: \"%\" in % is not a dynamic, so it settles nothing. Use "
            "one of %.".format(
                label, second, where, Marking.dynamics))
    }

    // `head[run]`, parsed as a run with resolved marks added to every leaf.
    // See Note [A marking group is a repeated suffix].
    *prNotationMarkingGroup { |token, head, whole, label, rests, containers,
        hairpinGroups|
        var at = this.prOutsideBraces(token, $[);
        var inside, children, leaves;
        if (token.endsWith("]").not) {
            Error(
                "%: % has text after its marking bracket. Put the length and any "
                "further markings inside the bracket."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        if (inside.isEmpty) {
            Error(
                "%: % needs at least one leaf. Use \"%[c4 d4]\"."
                .format(label, this.prLeafAt(token, whole), head[1])
            ).throw
        };
        children = this.prNotationChildren(inside, label, rests, "", containers,
            hairpinGroups);
        // Check the same leaves that will receive the mark.
        leaves = children.inject([], { |all, child|
            all ++ if (child.isKindOf(ScoreLeaf)) { [child] } { child.leaves } });
        leaves.do { |leaf|
            if (leaf.isKindOf(MusicRest)) {
                Error(
                    "%: % holds a rest. Write \"r4:%\" where the rest should carry the "
                    "mark."
                    .format(label, this.prLeafAt(token, whole), head[1])
                ).throw
            }
        };
        // Written order is outermost first. `attach` appends, so rebuild.
        // Two marks of one closed kind are then ordered by the setter.
        // See Note [Coincident marks of one kind have no order] in Marking.sc.
        // Reusing the `Marking` is safe: it has no leaf state.
        leaves.do { |leaf|
            var failure;
            try { leaf.markings_(head[0] ++ leaf.markings) }
                { |err| failure = err.what };
            if (failure.notNil) {
                this.prRefuseMarks(failure, this.prLeafAt(token, whole))
            }
        };
        ^children
    }

    // `direction[run]`, parsed as a run with hairpin endpoints
    // attached. See Note [A named group is the other bracket].
    *prNotationHairpin { |token, direction, whole, label, rests|
        var at = this.prOutsideBraces(token, $[);
        var inside, group, written;
        if (token.endsWith("]").not) {
            Error(
                "%: % has text after its hairpin bracket. Put ties and markings inside "
                "the bracket."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        inside = token.copyRange(at + 1, token.size - 2).stripWhiteSpace;
        // Keep the spelling for refusals.
        written = token.copyRange(0, at - 1);
        // Parser callers get wording that quotes the written head.
        group = if (inside.isEmpty) { [] } {
            this.prNotationChildren(inside, label, rests, "", true, false) };
        if (group.size < 2) {
            Error(
                "%: % needs at least two leaves, got %. Use \"%[c4 d4]\"."
                .format(
                    label,
                    this.prLeafAt(token, whole),
                    group.size,
                    written
                )
            ).throw
        };
        // Endpoints must land on leaves.
        [[group.first, "start"], [group.last, "stop"]].do { |end|
            if (end[0].isKindOf(ScoreLeaf).not) {
                Error(
                    "%: % has a bracket at its %. A group may hold a bracket between "
                    "its ends, but each end must be a leaf."
                    .format(label, this.prLeafAt(token, whole), end[1])
                ).throw
            }
        };
        // Direction data must have a helper.
        if (Spanner.respondsTo(direction).not) {
            Error(
                "%: % has no % group helper. Use Spanner.hairpinStart(%) and "
                "Spanner.hairpinStop on the two leaves."
                .format(
                    label,
                    this.prLeafAt(token, whole),
                    direction,
                    direction
                )
            ).throw
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
            Error(
                "%: % has text after its tuplet bracket. Put ties and markings inside "
                "the bracket."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        if (counts.isEmpty) {
            Error(
                "%: % is a tuplet bracket with no ratio. Use \"3:2[c4 d4 e4]\"."
                .format(label, this.prLeafAt(token, whole))
            ).throw
        };
        ^Tuplet.ratio(counts, this.prNotationChildren(
            token.copyRange(at + 1, token.size - 2), label, rests, "", true,
            hairpinGroups))
    }
}
