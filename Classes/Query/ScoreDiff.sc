// Note [A name is a label, a path is identity]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Renaming a staff is one fact, `\staffNameChanged`, not removal and
// re-addition of everything under it. A name is prose; an index is
// identity.
//
// A delta's `address` is the `ScoreSelection` path: one child index
// per level from the score. The score itself is `[]`.


// Note [Two passes, because a leaf is not a container]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// The structural pass walks raw children, so tuplets and empty
// containers are visible. `ScoreSelection` would miss empty
// containers.
//
// The leaf pass walks the same raw children and aligns leaves by
// path. It avoids `ScoreSelection` so half-finished editor states
// still diff. Structural and leaf passes report disjoint facts.


// ScoreDiff: what changed between two scores, as a list of plain facts.
//
// An observer only. It answers deltas and applies none of them: no
// patching, no undo, no move detection, no minimal edit script.
// Neither score is prepared or validated first, so an editor's
// half-finished state is diffed as readily as a finished one.
// `ScoreDiff.between(old, new)` answers an Array of records. The
// class methods below query one such Array.
ScoreDiff {

    // Every difference, structure first and then leaves in path
    // order.
    //
    // >>> ScoreDiff.between(MusicScore.oneStaff(Measure("2/4", "c4 d4")),
    //     MusicScore.oneStaff(Measure("2/4", "c4 d4"))).size
    // 0
    *between { |oldScore, newScore|
        var deltas = List.new;
        this.prRequireScore(oldScore, "the first");
        this.prRequireScore(newScore, "the second");
        this.prScoreDeltas(oldScore, newScore, deltas);
        this.prLeafPass(oldScore, newScore, deltas);
        ^deltas.asArray
    }

    // Diff addresses start at a score.
    *prRequireScore { |value, which|
        if (value.isKindOf(MusicScore).not) {
            Error("ScoreDiff.between: % score must be a MusicScore, got %."
                .format(which, value.class)).throw
        }
    }

    *prDelta { |deltas, kind, address, old, new|
        deltas.add(IdentityDictionary[
            \kind -> kind, \address -> address, \old -> old, \new -> new])
    }

    *prChanged { |deltas, kind, address, old, new|
        if (old != new) { this.prDelta(deltas, kind, address, old, new) }
    }

    // Position by position; nil marks a shorter side.
    *prPairs { |old, new|
        ^Array.fill(max(old.size, new.size)) { |i| [old[i], new[i]] }
    }

    *prScoreDeltas { |old, new, deltas|
        this.prChanged(deltas, \scoreTitleChanged, [], old.title, new.title);
        this.prChanged(deltas, \scoreComposerChanged, [],
            old.composer, new.composer);
        this.prPairs(old.children, new.children).do { |pair, index|
            var address = [index];
            case
            { pair[0].isNil } {
                this.prDelta(deltas, \staffAdded, address, nil, pair[1]) }
            { pair[1].isNil } {
                this.prDelta(deltas, \staffRemoved, address, pair[0], nil) }
            { true } {
                this.prStaffDeltas(pair[0], pair[1], address, deltas) }
        }
    }

    *prStaffDeltas { |old, new, address, deltas|
        this.prChanged(deltas, \staffNameChanged, address, old.name, new.name);
        this.prChanged(deltas, \staffShortNameChanged, address,
            old.shortName, new.shortName);
        this.prChanged(deltas, \staffClefChanged, address, old.clef, new.clef);
        this.prPairs(old.children, new.children).do { |pair, index|
            var here = address ++ [index];
            case
            { pair[0].isNil } {
                this.prDelta(deltas, \measureAdded, here, nil, pair[1]) }
            { pair[1].isNil } {
                this.prDelta(deltas, \measureRemoved, here, pair[0], nil) }
            { pair[0].isKindOf(Measure) and: { pair[1].isKindOf(Measure) } } {
                this.prMeasureDeltas(pair[0], pair[1], here, deltas) }
            { true } {
                this.prChildDeltas(pair[0], pair[1], here, deltas) }
        }
    }

    // `partialChanged` carries `[barDuration, metricOffset]`.
	// Either value alone is incomplete.
    *prMeasureDeltas { |old, new, address, deltas|
        this.prChanged(deltas, \meterChanged, address, old.meter, new.meter);
        this.prChanged(deltas, \partialChanged, address,
            [old.barDuration, old.metricOffset],
            [new.barDuration, new.metricOffset]);
        this.prChanged(deltas, \measureClefChanged, address,
            old.clef, new.clef);
        this.prChanged(deltas, \directionsChanged, address,
            old.directions.asArray, new.directions.asArray);
        this.prVoiceDeltas(old, new, address, deltas);
        this.prChildDeltas(old, new, address, deltas);
    }

    // `voices` counts timelines. A bar with no `Voice` children
    // counts one. Names compare only where both bars have named
    // voices.
    *prVoiceDeltas { |old, new, address, deltas|
        this.prChanged(deltas, \voiceCountChanged, address,
            old.voices.size, new.voices.size);
        if (old.hasVoices and: { new.hasVoices }) {
            this.prPairs(old.children, new.children).do { |pair, index|
                if (pair[0].isKindOf(Voice) and: { pair[1].isKindOf(Voice) }) {
                    this.prChanged(deltas, \voiceNameChanged, address ++ [index],
                        pair[0].name, pair[1].name)
                }
            }
        }
    }

    // Compare tuplets by printed counts.
	// 3:2 and 6:4 scale time alike.
    *prChildDeltas { |old, new, address, deltas|
        this.prPairs(old.children, new.children).do { |pair, index|
            var here = address ++ [index];
            var was = pair[0].isKindOf(ScoreContainer);
            var now = pair[1].isKindOf(ScoreContainer);
            case
            { was and: { now and: { pair[0].class == pair[1].class } } } {
                if (pair[0].isKindOf(Tuplet)) {
                    this.prChanged(deltas, \tupletRatioChanged, here,
                        [pair[0].actualNotes, pair[0].normalNotes],
                        [pair[1].actualNotes, pair[1].normalNotes])
                };
                this.prChildDeltas(pair[0], pair[1], here, deltas)
            }
            { was and: { now } } {
                this.prDelta(deltas, \elementRemoved, here, pair[0], nil);
                this.prDelta(deltas, \elementAdded, here, nil, pair[1])
            }
            { was } { this.prDelta(deltas, \elementRemoved, here, pair[0], nil) }
            { now } { this.prDelta(deltas, \elementAdded, here, nil, pair[1]) }
            { true } { nil }
        }
    }


    *prLeafPass { |old, new, deltas|
        var was = this.prByPath(old), now = this.prByPath(new);
        var paths = was.keys.asArray
            ++ now.keys.asArray.reject { |path| was.includesKey(path) };
        paths.sort { |a, b| ScoreDiff.prBeforePath(a, b) }.do { |path|
            var oldLeaf = was[path], newLeaf = now[path];
            case
            { oldLeaf.isNil } {
                this.prDelta(deltas, \leafAdded, path, nil, newLeaf) }
            { newLeaf.isNil } {
                this.prDelta(deltas, \leafRemoved, path, oldLeaf, nil) }
            { oldLeaf.class != newLeaf.class } {
                this.prDelta(deltas, \leafKindChanged, path, oldLeaf, newLeaf) }
            { true } {
                this.prLeafFields(oldLeaf, newLeaf, path, deltas) }
        }
    }

    // Raw children.
	// See Note [Two passes, because a leaf is not a container].
    *prByPath { |score|
        var table = Dictionary.new;
        this.prCollectLeaves(score, [], table);
        ^table
    }

    *prCollectLeaves { |element, path, table|
        if (element.isKindOf(ScoreContainer)) {
            element.children.do { |child, index|
                this.prCollectLeaves(child, path ++ [index], table) };
            ^this
        };
        table[path] = element
    }

    // Reading order, so a delta list runs down the page.
    *prBeforePath { |a, b|
        min(a.size, b.size).do { |i|
            if (a[i] != b[i]) { ^a[i] < b[i] }
        };
        ^a.size < b.size
    }

    // If a leaf changed class, fields are not compared.
    *prLeafFields { |old, new, address, deltas|
        this.prChanged(deltas, \durationChanged, address, old.dur, new.dur);
        this.prChanged(deltas, \pitchChanged, address,
            this.prPitchKey(old), this.prPitchKey(new));
        this.prChanged(deltas, \tieChanged, address,
            this.prTieKey(old), this.prTieKey(new));
        this.prChanged(deltas, \markingsChanged, address,
            old.markings.asArray, new.markings.asArray);
        this.prChanged(deltas, \spannersChanged, address,
            old.spanners.asArray, new.spanners.asArray);
        this.prChanged(deltas, \graceChanged, address,
            this.prGraceKey(old), this.prGraceKey(new));
    }

    // A leaf answers no `==`, so a grace group is compared by
    // content. Style is part of that content.
    *prGraceKey { |leaf|
        if (leaf.hasGraces.not) { ^nil };
        ^[leaf.graceStyle, leaf.graces.collect { |each| this.prLeafKey(each) }]
    }

    // Everything a grace leaf carries, nested graces included. Grace
    // leaves hang off leaves and are not reached by the leaf pass.
    *prLeafKey { |leaf|
        ^[leaf.class.name, this.prPitchKey(leaf), leaf.dur,
            this.prTieKey(leaf), leaf.markings.asArray, leaf.spanners.asArray,
            this.prGraceKey(leaf)]
    }

    // Pitch and tie keys shared by leaf and grace comparison.
    *prPitchKey { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches.asArray };
        if (leaf.isKindOf(MusicNote)) { ^leaf.pitch };
        ^nil
    }

    *prTieKey { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesToNext.asArray };
        if (leaf.isKindOf(MusicNote)) { ^leaf.tiesToNext == true };
        ^nil
    }


    // Queries over the Array `between` answers.

    // >>> ScoreDiff.noDifference([])   -> true
    *noDifference { |deltas| ^deltas.isEmpty }

    // Every delta about one address exactly.
    *deltasAt { |deltas, address|
        var steps = this.prAddress(address, "deltasAt");
        ^deltas.select { |delta| delta[\address] == steps }
    }

    // That address and everything beneath it. `[]` is the whole diff.
    *deltasUnder { |deltas, address|
        var steps = this.prAddress(address, "deltasUnder");
        ^deltas.select { |delta| ScoreDiff.prIsPrefix(steps, delta[\address]) }
    }

    *prIsPrefix { |prefix, path|
        if (prefix.size > path.size) { ^false };
        prefix.do { |step, i| if (step != path[i]) { ^false } };
        ^true
    }

    // Every address a diff names, once, in the order it named them.
    //
    // Compare by `==`; `includes` answers by identity.
    //
    // >>> ScoreDiff.addresses(ScoreDiff.between(
    //     MusicScore.oneStaff(Measure("1/4", "c4")),
    //     MusicScore.oneStaff(Measure("1/4", "d4"))))
    // [ [ 0, 0, 0 ] ]
    *addresses { |deltas|
        var seen = List.new;
        deltas.do { |delta|
            var address = delta[\address];
            if (seen.any { |each| each == address }.not) { seen.add(address) }
        };
        ^seen.asArray
    }

    // Which side of a diff an address belongs to.
    // nil means "not on this side", never "nothing there".
    *oldElementFor { |delta, oldScore|
        var checked = this.prDeltaShape(delta, "oldElementFor");
        if (this.prAddedKinds.includes(checked[\kind])) { ^nil };
        ^this.elementAtPath(oldScore, checked[\address])
    }

    *newElementFor { |delta, newScore|
        var checked = this.prDeltaShape(delta, "newElementFor");
        if (this.prRemovedKinds.includes(checked[\kind])) { ^nil };
        ^this.elementAtPath(newScore, checked[\address])
    }

    *prAddedKinds {
        ^#[\staffAdded, \measureAdded, \elementAdded, \leafAdded]
    }

    *prRemovedKinds {
        ^#[\staffRemoved, \measureRemoved, \elementRemoved, \leafRemoved]
    }

    *prDeltaShape { |delta, label|
        if (delta.isKindOf(IdentityDictionary).not) {
            Error("ScoreDiff.%: expected delta IdentityDictionary, got %."
                .format(label, delta.class)).throw
        };
        if (delta[\kind].isNil or: { delta[\address].isNil }) {
            Error("ScoreDiff.%: delta needs kind and address, got keys %."
                .format(label,
                    delta.keys.asArray.sort.asCompileString)).throw
        };
        ^delta
    }

    // What an address names, by the same raw walk both passes use.
    // `ScoreSelection#elementAtPath` is the ordinary-work version.
    //
    // >>> ScoreDiff.elementAtPath(
    //     MusicScore.oneStaff(Measure("1/4", "c4")), [0, 0, 0]).pitch
    // MusicPitch("c[4]")
    *elementAtPath { |score, address|
        var steps, here = score;
        if (score.isKindOf(MusicScore).not) {
            Error("ScoreDiff.elementAtPath: expected MusicScore, got %."
                .format(score.class)).throw
        };
        steps = this.prAddress(address, "elementAtPath");
        steps.do { |step|
            var children = if (here.isKindOf(ScoreContainer)) { here.children };
            if (children.isNil or: { step >= children.size }) {
                Error("ScoreDiff.elementAtPath: % does not resolve. % has % "
                    "children.".format(steps.asCompileString, here.class,
                        children !? { |each| each.size } ? 0)).throw
            };
            here = children[step]
        };
        ^here
    }

    // An Array of child indices, or one Integer for a single step.
    *prAddress { |address, label|
        var steps = if (address.isNumber) { [address] } { address };
        if (steps.isArray.not) {
            Error("ScoreDiff.%: address must be an Array of child indices, got "
                "% (%).".format(label, address.asCompileString,
                    address.class)).throw
        };
        steps.do { |step|
            if (step.isKindOf(Integer).not or: { step < 0 }) {
                Error("ScoreDiff.%: address % has invalid step %. Use "
                    "non-negative Integers.".format(label,
                        steps.asCompileString, step.asCompileString)).throw
            }
        };
        ^steps
    }

    // >>> ScoreDiff.countByKind([])   -> IdentityDictionary[  ]
    // >>> ScoreDiff.countByKind(ScoreDiff.between(
    //     MusicScore.oneStaff(Measure("1/4", "c4")),
    //     MusicScore.oneStaff(Measure("1/4", "d4"))))[\pitchChanged]
    // 1
    *countByKind { |deltas|
        var counts = IdentityDictionary.new;
        deltas.do { |delta|
            counts[delta[\kind]] = (counts[delta[\kind]] ? 0) + 1 };
        ^counts
    }

    // What a leaf carries, where the leaf itself stayed put.
    *leafFieldKinds {
        ^#[\pitchChanged, \durationChanged, \tieChanged, \markingsChanged,
            \spannersChanged, \graceChanged]
    }

    // Those, and whether a leaf is there at all.
    *leafKinds {
        ^#[\leafAdded, \leafRemoved, \leafKindChanged] ++ this.leafFieldKinds
    }

    // Structural and leaf deltas are complements. Field deltas are leaf content.
    *structuralDeltas { |deltas|
        ^deltas.reject { |delta| ScoreDiff.leafKinds.includes(delta[\kind]) }
    }

    *leafDeltas { |deltas|
        ^deltas.select { |delta| ScoreDiff.leafKinds.includes(delta[\kind]) }
    }

    *leafFieldDeltas { |deltas|
        ^deltas.select { |delta|
            ScoreDiff.leafFieldKinds.includes(delta[\kind]) }
    }
}
