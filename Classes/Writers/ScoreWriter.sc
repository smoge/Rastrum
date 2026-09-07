// A portable vocabulary belongs in the tree once LilyPond, MusicXML
// and ScoreJSON can carry the same musical fact. Backend-only facts
// stay out. Exact scalar domains may be broader than one writer's
// spelling vocabulary. A writer refuses those by name.


// Note [A writer refuses what it depends on]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `ScorePrepare` and `Validator` are facade steps. A raw writer still
// refuses invariants its spelling depends on.
//
// `LilyWriter.new.write(tree)` is a real entry point. If a malformed
// tree would make it emit wrong notation, the writer checks before
// spelling.

// LilyPond writes rehearsal marks and system text with `\\mark`.
// MusicXML writes tempo prose as `<words>` unless a metronome mark
// says speed. MusicXML draws a tempo ramp with words and dashes, but
// playback steps at the stop. LilyPond draws the ramp and, for MIDI,
// adds hidden tempo steps. GUIDO draws only clear
// ritardando/accelerando text.
//
// Accept losses only when they still say the score. Refuse unknown
// directions and unsafe ramp shapes.

// ScoreWriter: abstract visitor over the score tree.
//
// Every output format is a subclass. `GuidoWriter` documents its narrower
// boundary.
ScoreWriter {
    var <>stream;

    *new { ^super.new }

    write { |element|
        stream = CollStream.on(String.new);
        this.prepare(element);
        element.accept(this);
        ^stream.collection
    }

    prepare { |element| ^this }

    writeChildren { |container|
        container.children.do { |c| c.accept(this) };
        ^this
    }

    // LilyPond and MusicXML can place a short bar only against a barline.
    //
    // JSON does not call this. It stores facts and places nothing.
    prRequirePlaceableMeasure { |measure|
        if (measure.isPartial and: { measure.isAnacrusis.not }
            and: { measure.sitsAtBarline.not }) {
            Error(
                "%: a % bar of % beginning % into the meter touches neither barline. "
                "Use Measure.pickup or a barline-aligned Measure.partial."
                .format(
                    this.class.name,
                    measure.meter,
                    measure.barDuration,
                    measure.metricOffset
                )
            ).throw
        };
        ^this
    }

    // Emptiness only. The name says so. A chord's other invariant, that a
    // pitch appears once, is the model's: every backend spells a doubled
    // unison without complaint, so no writer's spelling depends on it.
    prRequireNonEmptyChord { |chord|
        if (chord.pitches.isEmpty) {
            Error(
                "%: a chord needs at least one pitch. Use a rest for silence."
                .format(this.class.name)
            ).throw
        };
        ^this
    }

    // Leaves, not children: an empty container has no bracket body.
    prRequireNonEmptyTuplet { |tuplet|
        if (tuplet.leaves.isEmpty and: { tuplet.isTrivial.not }) {
            Error(
                "%: a tuplet brackets no leaves. Add leaves or remove it."
                .format(this.class.name)
            ).throw
        };
        ^this
    }

    // Every leaf under an authored beam must carry a flag to beam.
    prRequireBeamableGroups { |element|
        AutoBeam.groupsIn(element).do { |group|
            group.do { |leaf|
                if (AutoBeam.isBeamable(leaf).not) {
                    Error(
                        "%: % under a beam has no flag to beam. End the beam before it, "
                        "or write it as a shorter value."
                        .format(this.class.name, leaf)
                    ).throw
                }
            }
        };
        ^this
    }

    // Note [Where a direction may sit]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // MusicXML carries exact `<offset>`. LilyPond and GUIDO are positional: a
    // mark can sit only where the note stream is already between leaves, in
    // every voice.
    //
    // So the model admits only the common place. `Validator` checks the
    // façade path. Positional writers check again on raw entry.

    // The leaf a point fact stands before.
    //
    // `offsets` is the caller's own `ScorePrepare.leafOffsetsIn(measure)`.
    // The refusal names the voice that has no leaf at this offset.
    prLeafAtSharedBoundary { |measure, offsets, offset, subject, rule|
        var local = { |leaf| offsets[leaf] - measure.metricOffset };
        var missing = measure.voices.detectIndex { |voice|
            voice.leaves.any { |leaf| local.(leaf) == offset }.not
        };
        if (missing.notNil) {
            Error(
                "%: % is written % into this bar, and voice % has no leaf there. %"
                .format(
                    this.class.name,
                    subject,
                    offset,
                    missing + 1,
                    rule
                )
            ).throw
        };
        // Every voice reaches it, so the first one's leaf is the place.
        ^measure.voices.first.leaves.detect { |each| local.(each) == offset }
    }

    // What to call the fact being placed, in a writer refusal.
    // `Validator` has its own wording for validation refusals.
    prSubjectOf { |direction|
        if (direction.isTempoRamp) {
            ^"a tempo ramp %".format(direction.edge)
        };
        // Only a metronome-only tempo has no words. Everything else is refused
        // at construction. Naming it beats interpolating a nil.
        ^direction.text !? { |words| "\"" ++ words ++ "\"" }
            ?? { "a % direction".format(direction.kind) }
    }

    // Mid-bar directions, keyed by the leaf they stand before.
    //
    // Positional backends call this. MusicXML carries exact offsets and
    // ScoreJSON stores facts. Both inherit this helper, but they must not call it.
    prDirectionsByLeaf { |measure|
        var pending = IdentityDictionary.new;
        var offsets;
        var mid = measure.directions.reject { |each|
            each.atBarStart or: { each.isTempoRamp } };
        if (mid.isEmpty) { ^pending };
        offsets = ScorePrepare.leafOffsetsIn(measure);
        mid.do { |direction|
            var leaf = this.prLeafAtSharedBoundary(measure, offsets,
                direction.offset, this.prSubjectOf(direction),
                "Directions must sit at a shared leaf boundary.");

            pending[leaf] = (pending[leaf] ? []) ++ [direction];
        };
        ^pending
    }

    // Tempo ramp endpoints, keyed by leaf.
    //
    // LilyPond and GUIDO place them the same way, then pair or check them
    // differently. MusicXML and ScoreJSON do not call this helper.
    prRampsByLeaf { |measure|
        var pending = IdentityDictionary.new;
        var ramps = measure.directions.select { |each| each.isTempoRamp };
        var offsets;

        if (ramps.isEmpty) { ^pending };
        offsets = ScorePrepare.leafOffsetsIn(measure);
        // `inSpanOrder` gives stop before start, so a leaf carrying both is
        // seen closing first.
        Validator.inSpanOrder(ramps).do { |endpoint|
            // The endpoint must land on a leaf boundary in every voice.
            var leaf = this.prLeafAtSharedBoundary(measure, offsets,
                endpoint.offset, this.prSubjectOf(endpoint),
                "Ramps must sit at a shared leaf boundary.");
            var here = pending[leaf] ? [];

            this.prCheckedRampsAt(here, endpoint);
            this.prTrackRamp(endpoint);
            pending[leaf] = here ++ [endpoint];
        };
        ^pending
    }

    // What one leaf may carry. GUIDO narrows this.
    prCheckedRampsAt { |here, endpoint| ^this }

    // How a backend pairs ramp endpoints as they are placed.
    prTrackRamp { |endpoint| ^this.subclassResponsibility(thisMethod) }

    // Note [One mark at one moment]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A leaf carries at most one sforzando and at most one dynamic, so each of
    // these answers the only one there.
    // See Note [One loudness slot on a leaf] in Marking.sc.
    // Reading the last is defensive, not a backend policy.
    //
    // >>> ScoreWriter.dynamicOf(MN("c4:sfz:pp")).value       -> pp
    // >>> ScoreWriter.sforzandoOf(MN("c4:sfz:pp")).value     -> f
    // >>> ScoreWriter.dynamicOf(MN("c4"))                    -> nil

    *dynamicOf { |leaf| ^leaf.dynamics.last }
    *sforzandoOf { |leaf| ^leaf.sforzandos.last }

    // Whether this backend has a spelling for a paired tempo ramp.
    //
    // >>> ScoreWriter.writesTempoRamps   -> false
    *writesTempoRamps { ^false }

    // Every direction writer's first line.
    //
    // Refuse before backend direction defaults can catch this.
    //
    // >>> try { ScoreWriter.prRequireWritableDirection(Direction.tempoRampStart("rit.")) } { \refused }
    // refused
    *prRequireWritableDirection { |direction|
        if (direction.isTempoRamp and: { this.writesTempoRamps.not }) {
            Error(
                "%: tempo ramps are spans, and this writer has no spelling for them "
                "yet. Use point tempo directions here."
                .format(this.name)
            ).throw
        };
        ^direction
    }

    visitNote      { ^this.subclassResponsibility(thisMethod)   }
    visitRest      { ^this.subclassResponsibility(thisMethod)   }
    visitChord     { ^this.subclassResponsibility(thisMethod)   }
    visitTuplet    { ^this.subclassResponsibility(thisMethod)   }
    // A voice is just its contents. Formats that preserve the voice node
    // override this.
    visitVoice     { |voice| ^this.writeChildren(voice)         }
    visitMeasure   { ^this.subclassResponsibility(thisMethod)   }
    visitStaff     { ^this.subclassResponsibility(thisMethod)   }
    visitScore     { ^this.subclassResponsibility(thisMethod)   }

    visitContainer { |container| ^this.writeChildren(container) }
}
