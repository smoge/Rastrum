// EventWriter's `\rastrum` payload carries similar facts for
// playback. Here the model's written and sounded durations are kept
// separate. `staffIndex` and `voiceIndex` are positional keys for the same
// reason EventWriter targets playback by index: names are optional and may
// repeat. A plain IdentityDictionary avoids spending a global class name on a
// struct.


// ScoreSelection: read leaf records in musical terms and rewrite a copy.
//
// `ScoreSelection(score).notes.inStaff(1).offsets` is the shape. One
// way in, filters answer another selection and accessors answer
// plain arrays. `mapLeaves` and `transposeBy` are write-side helpers:
// they change a copy. A leaf alone doesn't say where it is, so a
// selection holds records: the leaf plus the position facts that make
// it addressable.
ScoreSelection {
    var <records, <source;

    // Every leaf under a score, a staff or a bar, in reading order.
    //
    // >>> ScoreSelection(RhythmTree.measure(Meter(4, 4), [1, -1, 1, 1])).size
    // 4
    *new { |element|
        var found = List.new;
        var inScore = element.isKindOf(MusicScore);
        this.prStavesOf(element).do { |staff, staffIndex|
            // A staff is a path step only when the source is the score above it.
            this.prCollectStaff(staff, staffIndex,
                if (inScore) { [staffIndex] } { [] }, element, found)
        };
        ^this.fromRecords(found.asArray, element)
    }

    // A selection over records read elsewhere. Source is inferred
    // when possible. Empty selections still need a source if they
    // will transform a copy. Record order is caller-owned.
    //
    // ^ Self
    *fromRecords { |records, source|
        var list = records.asArray;
        ^super.newCopyArgs(list, this.prAgreedSource(list, source))
    }

    // Records and source must point at one tree. Transforms rebuild that tree by
    // path.
    *prAgreedSource { |records, source|
        var found;
        records.do { |record|
            var read = record[\source];
            if (read.notNil) {
                found = found ? read;
                if (read !== found) {
                    Error(
                        "ScoreSelection.fromRecords: these records were read from multiple trees. There is no single source to rewrite."
                    ).throw
                }
            }
        };
        if (source.isNil) { ^found };
        if (found.notNil and: { found !== source }) {
            Error(
                "ScoreSelection.fromRecords: the records were read from a different tree than the source given."
            ).throw
        };
        ^source
    }

    // A score is its staves. A staff is one. A bare bar is treated as
    // one staff's bar list without wrapping it in a Staff.
    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        if (element.isKindOf(Staff)) { ^[element] };
        if (element.isKindOf(Measure)) { ^[[element]] };
        Error(
            "ScoreSelection: expected MusicScore, Staff or Measure, got %."
            .format(element.class)
        ).throw
    }

    // Offsets and multipliers come from ScorePrepare.
    *prCollectStaff { |staff, staffIndex, staffPath, source, found|
        var inStaff = staff.isKindOf(Staff);
        var staffName = if (inStaff) { staff.name } { nil };
        var bars = if (inStaff) { staff.children.asArray } { staff };
        var barStart = Duration(0, 1);

        bars.do { |bar, barIndex|
            var offsets = ScorePrepare.leafOffsetsIn(bar);
            var multipliers = ScorePrepare.leafMultipliersIn(bar);
            // A bare bar is the source itself, so its own index is not a step.
            var barPath = if (bar === source) { [] } { staffPath ++ [barIndex] };
            bar.voices.do { |voice, voiceIndex|
                // A bar with no Voice children is its one timeline. No voice
                // path step is added.
                var voicePath = if (bar.hasVoices) {
                    barPath ++ [voiceIndex]
                } {
                    barPath
                };
                this.prCollectLeaves(voice, voicePath, (
                    staffIndex: staffIndex, staffName: staffName,
                    measureIndex: barIndex, measure: bar,
                    voiceIndex: voiceIndex,
                    voiceName: if (voice.isKindOf(Voice)) { voice.name } { nil },
                    barStart: barStart, source: source,
                    offsets: offsets, multipliers: multipliers), found)
            };
            barStart = barStart + bar.barDuration;
        }
    }

    // Collect leaf records by child-index path, so a path reaches through
    // nested tuplets and can be followed on a copy of the same shape.
    *prCollectLeaves { |element, path, where, found|
        element.children.do { |child, index|
            var childPath = path ++ [index];
            if (child.isLeaf) {
                found.add(this.prRecord(child, childPath, where))
            } {
                this.prCollectLeaves(child, childPath, where, found)
            }
        }
    }

    *prRecord { |leaf, path, where|
        // `leafOffsetsIn` counts from the bar's metric offset.
        var barOffset = where[\offsets][leaf] - where[\measure].metricOffset;
        var record = IdentityDictionary.new;
        record[\leaf] = leaf;
        record[\source] = where[\source];
        record[\path] = path;
        record[\staffIndex] = where[\staffIndex];
        record[\staffName] = where[\staffName];
        record[\measureIndex] = where[\measureIndex];
        record[\measure] = where[\measure];
        record[\voiceIndex] = where[\voiceIndex];
        record[\voiceName] = where[\voiceName];
        record[\barOffset] = barOffset;
        record[\offset] = where[\barStart] + barOffset;
        record[\written] = leaf.duration;
        record[\prolated] = leaf.duration * where[\multipliers][leaf];
        ^record
    }

    // ^ Integer
    size { ^records.size }
    // ^ Boolean
    isEmpty { ^records.isEmpty }
    // ^ Boolean
    notEmpty { ^records.notEmpty }
    at { |index| ^records[index] }
    first { ^records.first }
    last { ^records.last }
    do { |function| ^records.do(function) }
    collect { |function| ^records.collect(function) }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).leaves.last.class   -> MusicRest
    //
    // ^ [ScoreLeaf]
    leaves { ^records.collect { |record| record[\leaf] } }

    // the first selected leaf without the `.leaves.first` step
    //
    // nil for an empty selection
    //
    // >>> ScoreSelection(Measure("2/4", "c4 r4")).notes.firstLeaf.class   -> MusicNote
    //
    // ^ ScoreLeaf | Nil
    firstLeaf { ^records.first !? { |record| record[\leaf] } }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).lastLeaf.class   -> MusicRest
    //
    // ^ ScoreLeaf | Nil
    lastLeaf { ^records.last !? { |record| record[\leaf] } }

    // >>> ScoreSelection(Measure("2/4", "c4 d4")).offsets
    // [ Duration(0/1), Duration(1/4) ]
    //
    // ^ [Duration]
    offsets { ^records.collect { |record| record[\offset] } }

    // ^ [Duration]
    barOffsets { ^records.collect { |record| record[\barOffset] } }

    // >>> ScoreSelection(Measure("2/4", "c4 d4")).writtenDurations
    // [ Duration(1/4), Duration(1/4) ]
    //
    // ^ [Duration]
    writtenDurations { ^records.collect { |record| record[\written] } }
    // ^ [Duration]
    prolatedDurations { ^records.collect { |record| record[\prolated] } }

    // Distinct live Measure objects, in reading order.
    //
    // ^ [Measure]
    measures {
        var found = List.new;
        records.do { |record|
            var bar = record[\measure];
            if (found.any { |each| each === bar }.not) { found.add(bar) }
        };
        ^found.asArray
    }

    // Note [Pitch facts are read in two units]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `pitches` reads written pitch material. Chords expand for collection and
    // range queries, but adjacency queries refuse them because one chord has no
    // single melodic neighbor. `contour` reads height and cents. `intervals`
    // reads spelling. That makes c sharp to d flat `\same` to one and a written
    // step to the other.

    // Every selected pitch in score order. A note gives one, a chord gives all
    // of its written pitches in chord order, a rest gives none.
    //
    // >>> ScoreSelection(Measure("2/4", "<c e>4 r4")).pitches.size   -> 2
    //
    // ^ [MusicPitch]
    pitches {
        ^records.collect { |record|
            ScoreSelection.prPitchesOf(record[\leaf]) }.flatten(1)
    }

    // The lowest selected pitch, or nil where nothing pitched is selected.
    //
    // >>> ScoreSelection(Measure("2/4", "g4 c4")).lowestPitch.letter   -> c
    //
    // ^ MusicPitch | Nil
    lowestPitch { ^this.prExtreme(true) }

    // >>> ScoreSelection(Measure("2/4", "g4 c4")).highestPitch.letter   -> g
    //
    // ^ MusicPitch | Nil
    highestPitch { ^this.prExtreme(false) }

    // Strictly beyond replaces, so an exact tie in height and cents keeps
    // whichever came first in score order.
    //
    // ^ MusicPitch | Nil
    prExtreme { |wantLow|
        var found;
        this.pitches.do { |pitch|
            var beyond = if (found.isNil) { false } {
                if (wantLow) {
                    ScoreSelection.prBelow(pitch, found)
                } {
                    ScoreSelection.prBelow(found, pitch)
                }
            };
            if (found.isNil or: { beyond }) { found = pitch }
        };
        ^found
    }

    // Height first. Cents is only consulted where the ladder ties.
    *prBelow { |a, b|
        if (a.height != b.height) { ^a.height < b.height };
        ^a.cents < b.cents
    }

    // `[lowest, highest]`, or nil where nothing pitched is selected.
    //
    // >>> ScoreSelection(Measure("2/4", "g4 c4")).pitchRange.collect { |pitch| pitch.letter }
    // [ c, g ]
    //
    // ^ [MusicPitch] | Nil
    pitchRange {
        var low = this.lowestPitch;
        if (low.isNil) { ^nil };
        ^[low, this.highestPitch]
    }

    // The interval from the lowest selected pitch to the highest, spelled
    // rather than counted. `nil` where nothing pitched is selected.
    //
    // >>> ScoreSelection(Measure("2/4", "c4 g4")).pitchSpan.generic   -> 4
    //
    // ^ MusicInterval | Nil
    pitchSpan {
        var range = this.pitchRange;
        if (range.isNil) { ^nil };
        ^MusicInterval.between(range[0], range[1])
    }

    // Which way each adjacent pair moves: \up, \down or \same.
    //
    // By height, so an enharmonic repeat is \same where `intervals` answers a
    // written step. See Note [Pitch facts are read in two units].
    //
    // >>> ScoreSelection(Measure("2/4", "c#4 db4")).contour   -> [ same ]
    // >>> ScoreSelection(Measure("4/4", "c4 e4 d4 d4")).contour
    // [ up, down, same ]
    //
    // ^ [Symbol]
    contour {
        var list = this.prMelodic("contour");
        ^list.drop(1).collect { |pitch, index|
            var before = list[index];
            case
            { ScoreSelection.prBelow(before, pitch) } { \up }
            { ScoreSelection.prBelow(pitch, before) } { \down }
            { true } { \same }
        }
    }

    // The spelled interval from each pitch to the next.
    //
    // >>> ScoreSelection(Measure("2/4", "c#4 db4")).intervals.first
    // MusicInterval(1, 0)
    //
    // ^ [MusicInterval]
    intervals {
        var list = this.prMelodic("intervals");
        ^list.drop(1).collect { |pitch, index|
            MusicInterval.between(list[index], pitch) }
    }

    // A rest contributes no pitch, so it does not break adjacency.
    prMelodic { |label|
        records.do { |record|
            if (record[\leaf].isKindOf(Chord)) {
                Error(
                    "ScoreSelection.%: chord at %. Use `notes` for monophonic adjacency or `pitches` for every pitch."
                    .format(label, record[\path].asCompileString)
                ).throw
            }
        };
        ^this.pitches
    }

    // A chord reads as its pitches, a note as a chord of one, a rest as none.
    *prPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    printOn { |stream| stream << "ScoreSelection(" << records.size << ")" }
}
