// Note [The admission rule]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A portable value belongs in the tree once LilyPond, MusicXML and
// ScoreJSON can carry the same musical fact. Backend-only facts stay
// out.


// Note [A writer refuses what it depends on]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `ScorePrepare` and `Validator` are facade steps. A raw writer still
// refuses invariants its spelling depends on.
//
// `LilyWriter.new.write(tree)` is a real entry point. If a malformed
// tree would make it emit wrong notation, the writer checks before
// spelling.

// Note [What each backend cannot say]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// LilyPond writes rehearsal marks and system text with `\\mark`.
// MusicXML writes tempo prose as `<words>` unless a metronome mark
// says speed. MusicXML draws a tempo ramp with words and dashes, but
// playback steps at the stop. LilyPond draws the ramp and, for MIDI,
// adds hidden tempo steps. GUIDO draws only clear
// ritardando/accelerando text.
//
// Accept losses only when they still say the score. Refuse unknown
// directions and unsafe ramp shapes.

// Note [Where a direction may sit]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// MusicXML carries exact `<offset>`. LilyPond and GUIDO are
// positional: a mark can sit only where the note stream is already
// between leaves, in every voice.
//
// So the model admits only the common place. `Validator` checks the
// facade path. Positional writers check again on raw entry.


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
    // JSON does not call this; it stores facts and places nothing.
    prRequirePlaceableMeasure { |measure|
        if (measure.isPartial and: { measure.isAnacrusis.not }
            and: { measure.sitsAtBarline.not }) {
            Error("%: a % bar of % beginning % into the meter touches neither "
                "barline. Use Measure.pickup or a barline-aligned "
                "Measure.partial.".format(
                    this.class.name, measure.meter, measure.barDuration,
                    measure.metricOffset)).throw
        };
        ^this
    }

    // Note [Two marks at one moment]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The model and ScoreJSON keep every dynamic or sforzando written.
    //
    // A page has one glyph there, so drawing backends take the last
    // written. `PlaybackMap` resolves the same way.
    //
    // >>> ScoreWriter.dynamicOf(MN("c4:pp:p")).value         -> p
    // >>> ScoreWriter.sforzandoOf(MN("c4:sfz:sffz")).value   -> ff
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
    // >>> try { ScoreWriter.prRequireWritableDirection(
    //     Direction.tempoRampStart("rit.")) } { \refused }
    // refused
    *prRequireWritableDirection { |direction|
        if (direction.isTempoRamp and: { this.writesTempoRamps.not }) {
            Error("%: tempo ramps are spans, and this writer has no spelling "
                "for them yet. Use point tempo directions here.".format(
                    this.name)).throw
        };
        ^direction
    }

    visitNote      { ^this.subclassResponsibility(thisMethod)   }
    visitRest      { ^this.subclassResponsibility(thisMethod)   }
    visitChord     { ^this.subclassResponsibility(thisMethod)   }
    visitTuplet    { ^this.subclassResponsibility(thisMethod)   }
    visitVoice     { ^this.subclassResponsibility(thisMethod)   }
    visitMeasure   { ^this.subclassResponsibility(thisMethod)   }
    visitStaff     { ^this.subclassResponsibility(thisMethod)   }
    visitScore     { ^this.subclassResponsibility(thisMethod)   }

    visitContainer { |container| ^this.writeChildren(container) }
}
