// Note [Repeats copy measure-voice contents]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `repeatMeasures` copies whole measure-voice children, not `Measure`
// containers. Destination meter, metric offset, clef, directions and voice
// names stay. Filtered units such as `sources.notes` are partial and
// refused.
//
// Units are keyed by lane `[staffIndex, voiceIndex]` and bar. One
// source lane restarts in each destination lane. Multiple source
// lanes require the same destination lanes unless the destination is
// one gathered lane.

+ ScoreEdit {

    // Repeat whole measure-voice contents into destination measure voices.
    //
    // Read sources before rebuilding. Validate after installing all destinations
    // so a multi-bar tie can land.
    //
    // >>> { var score = MusicScore.oneStaff([Measure("2/4", "c4 d4"),
    //     Measure("2/4", "e4 f4"), Measure("2/4", "g4 a4")], "V");
    //     ScoreEdit.repeatMeasures(score, ScoreSelection(score).inMeasure(0),
    //     ScoreSelection(score).inMeasures(1, 2))
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ c, d, c, d, c, d ]
    //
    // ^ ScoreElement
    *repeatMeasures { |element, sources, destinations|
        var label = "repeatMeasures";
        var from = this.prCheckedUnits(element, sources, label, "bar to repeat");
        var into = this.prCheckedUnits(element, destinations, label,
            "bar to repeat into");

        ^Validator.validate(this.prRebuiltAll(element, [],
            this.prCycledUnits(from, into, label)))
    }

    // Whole measure voices, canonicalized by lane, then bar.
    //
    // ^ [IdentityDictionary]
    *prCheckedUnits { |element, selection, label, saying|
        var byKey = Dictionary.new;
        var seen = Dictionary.new;

        this.prCheckedSelectionOf(element, selection, label, saying);
        selection.records.do { |record|
            var key = [record[\staffIndex], record[\measureIndex],
                record[\voiceIndex]];
            // Keep duplicates visible: the call named this unit twice.
            if (seen.includesKey(record[\path])) {
                Error(
                    "ScoreEdit.%: the leaf at % is listed twice in the %. List it once."
                    .format(label, record[\path].asCompileString, saying)
                ).throw
            };
            seen[record[\path]] = true;
            byKey[key] = (byKey[key] ? []).add(record)
        };
        ^byKey.keys.asArray
            .sort { |a, b|
                // Lane before bar: staff, then voice, then measure index.
                if (a[0] != b[0]) { a[0] < b[0] } {
                    if (a[2] != b[2]) { a[2] < b[2] } { a[1] < b[1] } } }
            .collect { |key| this.prUnitAt(element, byKey[key], label, saying) }
    }

    // Build one unit's path and ensure it was selected whole.
    //
    // ^ IdentityDictionary
    *prUnitAt { |element, records, label, saying|
        var first = records.first;
        var measure = first[\measure];
        var staffPath = if (element.isKindOf(MusicScore)) {
            [first[\staffIndex]] } { [] };
        var barPath = if (measure === element) { [] } {
            staffPath ++ [first[\measureIndex]] };
        var path = if (measure.hasVoices) {
            barPath ++ [first[\voiceIndex]] } { barPath };
        var node = measure.voices[first[\voiceIndex]];
        var chosen = Dictionary.new;
        var unit = IdentityDictionary.new;

        records.do { |record| chosen[record[\path]] = true };
        this.prLeafPathsUnder(node, path).do { |each|
            if (chosen.includesKey(each).not) {
                Error(
                    "ScoreEdit.%: the % at % is only partly selected; % is missing."
                    .format(
                        label,
                        saying,
                        path.asCompileString,
                        each.asCompileString
                    )
                ).throw
            }
        };
        unit[\path] = path;
        unit[\lane] = [first[\staffIndex], first[\voiceIndex]];
        unit[\children] = node.children.asArray;
        ^unit
    }

    // Which source each destination takes, as `path -> children`.
    //
    // ^ Dictionary
    *prCycledUnits { |from, into, label|
        var sourceLanes = this.prLanesOf(from);
        var destinationLanes = this.prLanesOf(into);
        var wanted = Dictionary.new;

        // One destination lane is an explicit gathered target.
        if (destinationLanes.size == 1) {
            into.do { |unit, index|
                wanted[unit[\path]] = from.wrapAt(index)[\children] };
            ^wanted
        };
        if (sourceLanes.size > 1 and: { sourceLanes != destinationLanes }) {
            Error(
                "ScoreEdit.%: the source covers lanes % and the destination covers %. "
                "Repeat several lanes into several only where the lanes are the same, or take one lane at a time."
                .format(
                    label,
                    sourceLanes.asCompileString,
                    destinationLanes.asCompileString
                )
            ).throw
        };
        destinationLanes.do { |lane|
            var row = if (sourceLanes.size == 1) { from } {
                from.select { |unit| unit[\lane] == lane } };
            into.select { |unit| unit[\lane] == lane }.do { |unit, index|
                wanted[unit[\path]] = row.wrapAt(index)[\children] }
        };
        ^wanted
    }

    // Distinct lanes, in the order the canonicalized units give them.
    //
    // ^ [(Integer, Integer)]
    *prLanesOf { |units|
        var found = [];
        units.do { |unit|
            if (found.any { |each| each == unit[\lane] }.not) {
                found = found.add(unit[\lane])
            }
        };
        ^found
    }

    // Replace several containers' children in one copy walk. Caller
    // validates.
    //
    // >>> { var bar = Measure("2/4", [Voice("c4 d4", "upper"),
    //     Voice("e,2", "lower")]);
    //     var wanted = Dictionary.new;
    //     var edited;
    //     wanted[[0]] = Voice("g4 a4").children;
    //     edited = ScoreEdit.prRebuiltAll(bar, [], wanted);
    //     [edited.children.first.name,
    //         edited.leaves.collect { |leaf| leaf.pitch.letter }] }.value
    // [ upper, [ g, a, e ] ]
    //
    // ^ ScoreElement
    *prRebuiltAll { |element, path, replacements|
        var children = replacements[path];

        if (children.notNil) {
            ^ScorePrepare.rebuilt(element,
                children.collect { |child| ScorePrepare.copyOf(child) })
        };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, index|
                this.prRebuiltAll(child, path ++ [index], replacements) })
    }

}
