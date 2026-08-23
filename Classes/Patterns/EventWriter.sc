// Note [The event payload is structural]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// EventWriter puts Rastrum metadata under one `\rastrum` key.
// `PatternWriter` carries it unchanged; interpretation layers may
// read it. Playback identity is `[staffIndex, timelineIndex]`. Staff
// and voice names are labels only. `dur` is a Float in SC beats.
// Payload `duration` and `offset` are exact Durations in whole notes.
// The lossy scheduler value sits beside the exact one. Payload
// `markings` and `spanners` are model objects with offsets. A tied
// run is one event, so continuation-leaf attachments keep positions.

// Note [Ties become sounding runs]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A tie extends one pitch, not one leaf. Chords therefore become
// pitch streams first. Runs with the same offset and duration join
// back into one event. A tied continuation doesn't attack, but can
// carry markings or spanner endpoints. Each run keeps the leaves it
// crossed.


// EventWriter: a score as structural event data.
//
// Not a ScoreWriter: event offsets and ties need timeline context.
// Model classes still don't answer Events. Grace groups aren't played
// here. Performance choices belong above this layer.
EventWriter {

    // Grouped by staff, then by timeline.
    //
    // The tree is read as it stands. `Rastrum.events` prepares and
    // validates first.
    //
    // >>> EventWriter.events(Measure("2/4", "c4 d4")).size   -> 2
    // >>> EventWriter.events(Measure("2/4", "c4 r4")).last[\midinote] -> nil
    *events { |element|
        var events = List.new;
        this.prStavesOf(element).do { |staff, index|
            this.prStaffEvents(staff, index).do { |event| events.add(event) }
        };
        ^events.asArray
    }

    // A score has staves. Anything else is one staff-like root.
    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        ^[element]
    }

    // A timeline is the unit a tie can span.
    *prStaffEvents { |staff, staffIndex|
        // Only a Staff has a staff name. A bare Voice name is a timeline name.
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

    // One timeline across the bar sequence.
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
                // Each bar is read on its own.
                this.prCollect(timeline, barStart, Duration(1, 1), measureIndex,
                    if (timeline.isKindOf(Voice)) { timeline.name } { nil }, found);
            };
            barStart = barStart + span;
        };
        ^this.prGroupRuns(this.prSoundingRuns(found.asArray),
            staffName, staffIndex, timelineIndex);
    }

    // Gather leaves in sounding order and return the offset after `element`.
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

    // One record per continuous sounding pitch.
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
                        // No attack, but it can carry payload attachments.
                        run[\duration] = run[\duration] + entry[2];
                        run[\leaves].add([entry[0], entry[1]]);
                    };
                    if (ties[index]) { pending[pitch] = run } { pending.removeAt(pitch) };
                };
            };
        };
        if (pending.notEmpty) {
            Error("EventWriter: % ties onward with no following note. Validate "
                "the score before writing events.".format(
                    pending.keys.asArray)).throw
        };
        ^runs.asArray
    }

    // Keeps each leaf crossed by the run for attachment payloads.
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

    // One flag per pitch, in pitch order. Notes read as one-pitch chords.
    *prTiesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesToNext.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.tiesToNext] };
        ^[]
    }

    *prRequireContinuations { |pending, pitches, leaf|
        pending.keysDo { |pitch|
            if (pitches.any { |arriving| arriving == pitch }.not) {
                Error("EventWriter: % ties onward into %, which does not contain "
                    "that pitch.".format(pitch, leaf)).throw
            }
        };
        ^this
    }

    // Runs sharing offset and duration are one event.
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

    // By onset, then low-to-high for simultaneous attacks.
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

    // Answers `[markings, spanners]`, each record with written
    // offset. See Note [The event payload is structural].
    //
    // Deduped per leaf, not per run.
    //
    // Arrays are copied; immutable objects are shared.
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

        // The one lossy value. The exact Duration travels in the payload.
        event[\dur] = first[\duration].asFloat * 4;
        payload[\duration] = first[\duration];
        payload[\offset] = first[\offset];
        // Names are labels. Indexes are identity.
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
        // One pitch stays a number. Several become an array.
        event[\midinote] = if (group.size == 1) {
            first[\pitch].midinote
        } {
            group.collect { |run| run[\pitch].midinote }
        };
        ^event
    }
}
