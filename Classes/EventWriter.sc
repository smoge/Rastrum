// EventWriter: a score as event data.
//
// The one output boundary that is not a file. Everything else here writes text
// for something else to read. This answers Events, which is what a pattern or a
// scheduler in this language actually consumes.
//
// NOT A ScoreWriter: an event needs where it begins, which is an accumulation
// along a timeline, and a tied note needs the leaf after it. Double dispatch
// cannot ask either question, so this walks the tree itself. It is still a
// boundary: model classes do not answer Events, and this class reads the tree
// without changing it.
//
// A tie is not a new attack, so a tied run leaves as one event lasting its sum.
// A chord ties per pitch. `prSoundingRuns` and `prGroupRuns` state that rule
// where it is enforced.
//
// A grace group produces no event, and is not in the payload either. What a
// grace note is worth in time is a performance decision rather than a fact of
// the score, so it belongs with the layer that decides what `ff` is worth. Read
// as absent until a PlaybackMap says otherwise, not as forgotten.


// Note [Structure travels in one payload]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Everything Rastrum knows about an event that SC does not goes under one
// `\\rastrum` key: exact duration, offset, staff, timeline, bar number,
// markings, and spanners.
//
// One key because an Event is also a bag of SynthDef controls. `measure`,
// `offset` and `staffIndex` are all names a user's SynthDef might legitimately
// take, and loose keys would hand it a bar number where it wanted a control.
// Namespaced, they cannot collide with anything.
//
// The payload is inert: nothing here reads it back, `PatternWriter` carries it
// through untouched, and `PlaybackMap` is the first thing that looks inside.


// Note [A timeline is named by index, not by name]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every payload carries `staffIndex` and `timelineIndex` beside `staff` and
// `voice`, because a name is optional and repeats. Two unnamed voices are
// indistinguishable by name, and an `"upper"` in a violin staff is not the
// `"upper"` in a piano staff.
//
// So the pair of indices is the identity that cannot disappear, and anything
// addressing a timeline uses it. `PlaybackMap` targets by it, and accepts a
// name only as a convenience resolved against the score.


// Note [Exact and inexact, side by side]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every event carries `duration` and `offset` as exact Durations inside its
// `\rastrum` payload, and `dur` as a top-level Float beside them. The Float is
// the only lossy value this project produces, and it exists because SC's
// scheduler takes Floats. Keeping the exact value next to it means the loss is
// visible at the one place it happens rather than being the only thing that
// survived.
//
// The two also disagree on units. `dur` counts beats, which is what `\dur`
// means in an SC Event: a quarter note is 1.0. `\rastrum[\duration]` counts
// whole notes, which is what a Duration means here, so the same quarter is 1/4.
// They answer to different worlds. Whole notes under a key SC reads as beats
// would make the most obvious use of this four times too fast.


// Note [Attachments are carried, not read]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every payload has `markings` and `spanners`, empty when a leaf carried none.
// They hold the model's own `Marking`s and `Spanner` endpoints, and nothing
// here decides what a `ff` or a slur should do to a sound. That choice belongs
// to a layer somebody asked for. A `Marking` in an Event says nothing about
// loudness.
//
// Carrying them here rather than letting such a layer walk the tree is what
// keeps one answer: `ScorePrepare` puts a split leaf's markings on its first
// piece, and a second traversal would have to reproduce that rule exactly or
// land a dynamic on the wrong half of a tie.
//
// The offset each travels with is not decoration. A tied run collapses into one
// event, so anything written on a continuation leaf lands inside that event
// rather than at its start: a slur can stop there, and a dynamic can change
// there. `MusicNote(60, q, tie).dynamic(\p)` then `MusicNote(60, q)
// .dynamic(\ff)` is one sounding note that gets louder halfway. There is no
// second attack for a reader to recover that from. `prRunFor` keeps offsets.
// `prAttachmentsOf` emits them.


// Walks the tree and answers an Array of Events, grouped by staff and then by
// timeline, each timeline in the order its events begin.
EventWriter {

    // Returns an Array of Events, grouped by staff and then by timeline. Within
    // one timeline they are in the order they begin.
    //
    // Grouped rather than sorted by onset across the whole score, because a
    // Pbind is one linear stream: a voice's events being contiguous and in
    // order is the shape anything downstream can use, and a global onset sort
    // would have to be undone to build one. Two voices of a bar therefore both
    // begin at offset zero, one after the other in this array, which is what
    // simultaneity looks like written down in a line.
    //
    // The tree is read as it stands. `Rastrum.events` prepares and validates
    // first, which is what makes ties reach something and bars add up.
    *events { |element|
        var events = List.new;
        this.prStavesOf(element).do { |staff, index|
            this.prStaffEvents(staff, index).do { |event| events.add(event) }
        };
        ^events.asArray
    }

    // A score is its staves, a bare staff is one, and anything else is a
    // timeline with no staff around it, which still has events.
    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        ^[element]
    }

    // A staff is walked one timeline at a time, from its first bar to its last.
    //
    // Not bar by bar: a tie crossing a barline has its continuation in the next
    // measure, so merging within a bar would find a tie reaching nothing. A
    // timeline is the unit a tie can span, which is also the unit a voice is.
    *prStaffEvents { |staff, staffIndex|
        // Only a Staff has a staff name. Asking whether the root *responds* to
        // `name` caught a bare Voice too, which then claimed its own name was
        // the part it was in.
        var name = if (staff.isKindOf(Staff)) { staff.name } { nil };
        var measures = if (staff.isKindOf(Staff)) { staff.children.asArray } { [staff] };
        var count = measures.collect { |measure| this.prTimelinesOf(measure).size }
            .reduce { |a, b| max(a, b) } ? 1;
        var events = List.new;

        count.do { |timelineIndex|
            this.prTimelineEvents(measures, timelineIndex, name, staffIndex)
                .do { |event| events.add(event) }
        };
        ^events.asArray
    }

    *prTimelinesOf { |measure|
        if (measure.isKindOf(Measure)) { ^measure.voices };
        ^[measure]
    }

    // Every bar contributes its own share of one timeline, and every voice of a
    // bar begins where the bar begins, voices run alongside each other, so
    // threading one cursor through them all would put the second after the
    // first.
    *prTimelineEvents { |measures, timelineIndex, staffName, staffIndex|
        var found = List.new;
        var barStart = Duration(0, 1);

        measures.do { |measure, measureIndex|
            var timelines = this.prTimelinesOf(measure);
            var timeline = if (timelineIndex < timelines.size) {
                timelines[timelineIndex]
            } {
                nil
            };
            var span = if (measure.isKindOf(Measure)) {
                measure.barDuration
            } {
                measure.duration * measure.multiplier
            };

            timeline !? {
                // The name is read per bar, not once for the timeline: a staff
                // whose bars disagree about a voice's name would otherwise
                // report the last one for all of them. `ScorePrepare` refuses
                // that shape, but this class is reachable without it.
                this.prCollect(timeline, barStart, Duration(1, 1), measureIndex,
                    if (timeline.isKindOf(Voice)) { timeline.name } { nil }, found);
            };
            barStart = barStart + span;
        };
        ^this.prGroupRuns(this.prSoundingRuns(found.asArray),
            staffName, staffIndex, timelineIndex);
    }

    // Gathers leaves in the order they sound, each with where it begins and how
    // long it lasts, both in sounding time, since that is what is played.
    // Answers the offset just past the element, so a container can thread the
    // cursor through its children.
    *prCollect { |element, offset, multiplier, measureIndex, voiceName, found|
        var inner, cursor;
        if (element.isLeaf) {
            found.add([element, offset, element.duration * multiplier,
                measureIndex, voiceName]);
            ^offset + (element.duration * multiplier)
        };
        inner = multiplier * element.multiplier;
        cursor = offset;
        element.children.do { |child|
            cursor = this.prCollect(child, cursor, inner, measureIndex, voiceName, found)
        };
        ^cursor
    }

    // Returns what actually sounds, as one record per continuous pitch.
    //
    // A tie is not a re-attack, and a chord ties per pitch, so a chord is read
    // as several simultaneous pitch streams rather than as one object. A pitch
    // arriving from a tie extends the run it is continuing. A pitch arriving
    // without one starts a new run. That single rule covers a whole chord tie,
    // a partial one, a note tying into a chord that holds its pitch, and a
    // chord pitch tying on into a note.
    //
    // What a tie reaches must contain the pitch it continues. A tie into a
    // rest, into a different note, or into a chord missing that pitch is
    // refused, since each would silently drop or lengthen something.
    *prSoundingRuns { |entries|
        var runs = List.new;
        var pending = Dictionary.new;

        entries.do { |entry|
            var leaf = entry[0];
            var pitches = this.prPitchesOf(leaf);
            var ties = this.prTiesOf(leaf);

            this.prRequireContinuations(pending, pitches, leaf);
            if (leaf.isKindOf(MusicRest)) {
                runs.add(this.prRunFor(nil, entry));
            } {
                pitches.do { |pitch, index|
                    var run = pending[pitch];
                    if (run.isNil) {
                        run = this.prRunFor(pitch, entry);
                        runs.add(run);
                    } {
                        // Continuing: the run grows and no attack is recorded,
                        // but the leaf still joins it. What it carries was
                        // written and has to go somewhere.
                        run[\duration] = run[\duration] + entry[2];
                        run[\leaves].add([entry[0], entry[1]]);
                    };
                    if (ties[index]) { pending[pitch] = run } { pending.removeAt(pitch) };
                };
            };
        };
        if (pending.notEmpty) {
            Error("EventWriter: % ties onward with nothing after it. A tie must "
                "reach some note; run Validator.validate, or go through "
                "Rastrum.events, which validates.".format(
                    pending.keys.asArray)).throw
        };
        ^runs.asArray
    }

    // `leaves` is every leaf this run passes through, each with the offset it
    // began at: the attack, then whatever tied on after it. A run collapses
    // into one event, so this is the only record of what the continuations
    // carried. See Note [Attachments are carried, not read].
    *prRunFor { |pitch, entry|
        ^(pitch: pitch, offset: entry[1], duration: entry[2],
            measure: entry[3], voice: entry[4],
            leaves: List[[entry[0], entry[1]]])
    }

    *prPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    // One flag per pitch, in the same order, so a chord's mask lines up with
    // its pitches and a note reads as a chord of one.
    *prTiesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesToNext.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.tiesToNext] };
        ^[]
    }

    *prRequireContinuations { |pending, pitches, leaf|
        pending.keysDo { |pitch|
            if (pitches.any { |arriving| arriving == pitch }.not) {
                Error("EventWriter: % ties onward into %, which does not hold it. "
                    "A tie continues one pitch, so what it reaches has to contain "
                    "that pitch - otherwise a note is lost or "
                    "lengthened.".format(pitch, leaf)).throw
            }
        };
        ^this
    }

    // Returns events, one per group of runs that begin together and last as
    // long as each other.
    //
    // That is what keeps an ordinary chord one event: its pitches share an
    // offset and a duration, so they are one attack. A partially tied chord
    // does not: the tied pitches outlast the others, so it becomes the
    // several attacks it actually is. Within a timeline only chord pitches can
    // share an offset, so nothing else is ever merged here.
    *prGroupRuns { |runs, staffName, staffIndex, timelineIndex|
        var groups = List.new;
        var byKey = Dictionary.new;

        runs.do { |run|
            var key, group;
            if (run[\pitch].isNil) {
                groups.add([run]);
            } {
                key = [run[\offset], run[\duration]];
                group = byKey[key];
                if (group.isNil) {
                    group = List.new;
                    byKey[key] = group;
                    groups.add(group);
                };
                group.add(run);
            };
        };
        ^this.prOrdered(groups.collect { |group|
            this.prEventFor(group, staffName, staffIndex, timelineIndex)
        });
    }

    // By onset, then from the bottom of the chord up, so the order is the one
    // it would be read in rather than the order the ties happened to close in.
    *prOrdered { |events|
        ^events.asArray.sort { |a, b|
            if (a[\rastrum][\offset] == b[\rastrum][\offset]) {
                this.prLowestOf(a) <= this.prLowestOf(b)
            } {
                a[\rastrum][\offset] < b[\rastrum][\offset]
            }
        }
    }

    *prLowestOf { |event|
        var midinote = event[\midinote];
        if (midinote.isNil) { ^0 };
        if (midinote.isSequenceableCollection) { ^midinote.minItem };
        ^midinote
    }

    // Returns `[markings, spanners]` for one event: what the leaves behind it
    // carried, each with the offset it was written at: markings as `(offset:,
    // marking:)`, endpoints as `(offset:, spanner:)`. See
    // Note [Attachments are carried, not read] for why both of those travel.
    //
    // Once per leaf, not once per run. An ordinary chord is several pitch
    // streams that come back as one event, and all of them point at the same
    // leaf, so the chord's dynamic would otherwise be said once per pitch.
    // Identity is the test rather than equality: two leaves may carry equal
    // markings and both meant it.
    //
    // Both arrays are the event's own. The Markings and Spanners inside are
    // shared with the tree, which is safe, neither has a setter, but the
    // array is not, so a reader that grows what it was handed cannot write into
    // the score.
    *prAttachmentsOf { |group|
        var seen = IdentitySet.new;
        var markings = List.new;
        var spanners = List.new;

        group.do { |run|
            run[\leaves].do { |pair|
                var leaf = pair[0];
                var at = pair[1];
                if (seen.includes(leaf).not) {
                    seen.add(leaf);
                    leaf.markings.do { |marking|
                        markings.add(IdentityDictionary[
                            \offset -> at, \marking -> marking])
                    };
                    leaf.spanners.do { |endpoint|
                        spanners.add(IdentityDictionary[
                            \offset -> at, \spanner -> endpoint])
                    };
                };
            };
        };
        ^[markings.asArray, spanners.asArray]
    }

    *prEventFor { |group, staffName, staffIndex, timelineIndex|
        var first = group.first;
        var event = Event.new;
        var payload = IdentityDictionary.new;
        var isRest = first[\pitch].isNil;
        var attachments;

        // The one lossy value, in beats where the Duration beside it is in
        // whole notes. See Note [Exact and inexact, side by side].
        event[\dur] = first[\duration].asFloat * 4;
        payload[\duration] = first[\duration];
        payload[\offset] = first[\offset];
        // Both, by Note [A timeline is named by index, not by name].
        payload[\staff] = staffName;
        payload[\staffIndex] = staffIndex;
        payload[\voice] = first[\voice];
        payload[\timelineIndex] = timelineIndex;
        payload[\measure] = first[\measure];
        payload[\rest] = isRest;
        attachments = this.prAttachmentsOf(group);
        payload[\markings] = attachments[0];
        payload[\spanners] = attachments[1];
        event[\rastrum] = payload;

        if (isRest) {
            event[\type] = \rest;
            ^event
        };
        event[\type] = \note;
        // One pitch stays a number and several become an array, so an ordinary
        // note and an ordinary chord read as they always did. The grouping is
        // invisible until a partial tie makes it visible.
        event[\midinote] = if (group.size == 1) {
            first[\pitch].midinote
        } {
            group.collect { |run| run[\pitch].midinote }
        };
        ^event
    }
}
