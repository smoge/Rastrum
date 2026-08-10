// ScoreWriter: abstract visitor over the score tree.
//
// Every output format is a subclass and touches nothing else. Three ship with
// the quark: LilyWriter, MusicXMLWriter, ScoreJSONWriter.
//
// Note [The admission rule]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A value belongs in the tree once every writer can spell it *the same way*.
//
// Agreement, not capability. Capability is the weaker test. Both backends can
// put a third of a semitone on a page: MusicXML
// writes `<alter>0.3333</alter>` and LilyPond rounds to the nearest quarter
// tone. Both succeed, and the same tree has become two pieces of music. So the
// admitted set is where they agree today, not the far edge of either, and it
// moves the day both spell the same finer thing.
//
// What only one writer can say belongs in that writer's options, not in the
// model. What they say differently belongs nowhere until they agree.


// Note [A writer refuses what it depends on]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `ScorePrepare` and `Validator` are wired into the facade, not into the
// writers. A writer stays literal: it spells the tree it is handed and throws
// on one it cannot, which is what keeps it testable on exactly that tree and
// keeps a bad tree a loud failure rather than a quiet repair.
//
// The cost is that `LilyWriter.new.write(tree)` is a real entry point with
// nothing in front of it. So a rule a writer *depends* on is stated in the
// writer as well as in `Validator`. `prRequirePlaceableMeasure` below is one,
// and `LilyWriter#prDirectionsByLeaf` is another. A rule a writer only assumes
// is a rule with a hole in it, and the hole is the shape of whoever called
// `write` directly.

// Note [What each backend cannot say]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// The admission rule above keeps a *value* out of the model unless every writer
// can spell it. It says nothing about the *distinctions* the model draws, and
// two of those survive unevenly. Both are losses in the output rather than in
// the tree, and both are named here rather than discovered.
//
// LilyPond has one construct for an item over the system, `\\mark`, so a
// rehearsal mark and system text come out identically. The difference survives
// in MusicXML, which has `<rehearsal>` and `<words>`, and in ScoreJSON. It is
// lost in the `.ly`.
//
// MusicXML has only `<words>` for prose, so a tempo carrying no metronome mark
// arrives as text and an importer cannot tell it was a tempo. `<metronome>` is
// what draws one and `<sound tempo="...">` what plays it, and both need the
// count. That is why a metronome mark is the part of a tempo that travels.

// Note [Where a direction may sit]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// The admission rule, worked through on one case.
//
// MusicXML measures a direction's `<offset>` in divisions and carries any value
// exactly. LilyPond has no offset syntax at all: a mark takes effect where it
// stands in the note stream, so it can only sit where that stream is already
// between leaves, and in a bar of several voices, between leaves in every
// one of them at once.
//
// So the model admits the narrower of the two. An offset that only MusicXML
// could place is exactly what would drift the tree into one backend's shape.
//
// `Validator` refuses one that does not land there, and `LilyWriter` refuses it
// again by Note [A writer refuses what it depends on].
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

    // A short bar has to be placed on paper, and both notation formats can
    // place one only against a barline: ending at the next one, which is an
    // anacrusis, or beginning at this one, which is a truncated bar. Neither
    // can float a span between the two, and writing it as one of the neighbors
    // it is not would be a valid file saying the wrong thing about where the
    // music sits, the one failure a reader could not spot. So both refuse,
    // from here, so the rule has one definition rather than two that can drift.
    //
    // The interchange writer does not call this: JSON stores the two facts and
    // places nothing, so it has nothing to be unable to spell.
    prRequirePlaceableMeasure { |measure|
        if (measure.isPartial and: { measure.isAnacrusis.not }
            and: { measure.sitsAtBarline.not }) {
            Error("%: a % bar of % beginning % into the meter touches neither "
                "barline. A short bar goes against the one it ends at - "
                "Measure.pickup - or the one it begins at - Measure.partial - "
                "and cannot float between them.".format(
                    this.class.name, measure.meter, measure.barDuration,
                    measure.metricOffset)).throw
        };
        ^this
    }

    visitNote      { ^this.subclassResponsibility(thisMethod) }
    visitRest      { ^this.subclassResponsibility(thisMethod) }
    visitChord     { ^this.subclassResponsibility(thisMethod) }
    visitTuplet    { ^this.subclassResponsibility(thisMethod) }
    visitVoice     { ^this.subclassResponsibility(thisMethod) }
    visitMeasure   { ^this.subclassResponsibility(thisMethod) }
    visitStaff     { ^this.subclassResponsibility(thisMethod) }
    visitScore     { ^this.subclassResponsibility(thisMethod) }
    visitContainer { |container| ^this.writeChildren(container) }
}
