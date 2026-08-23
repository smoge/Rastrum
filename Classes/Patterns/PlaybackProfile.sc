// Note [One order, stated once]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
//   EventWriter -> PlaybackMap -> PlaybackGraceMap -> PlaybackControlMap
//     -> PlaybackEnvelopeMap -> PatternWriter -> PlaybackTempoMap.withScoreTempo
//
// Each step reads what the previous one wrote. Tempo is last because
// it is a lane beside the music, not an event key.
//
// A `PlaybackTempoMap` instance isn't a slot here. `withScoreTempo`
// needs no table. Prose tempo resolution is a separate interpretation
// pattern.

// Note [One key, one layer]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Each layer refuses the keys it can name. Cross-layer clashes are
// visible only here: a control named `\panLevels` and an envelope for
// `\pan` both write `panLevels`.
//
// Refuse clashes here so layer order does not decide the sound.


// PlaybackProfile: the playback layers of a score, composed in one place.
//
// Four event layers, then tempo wrapping the finished pattern. The
// class owns that order and the keys it carries.
//
// Playback-side only. The maps sit beside a score and don't change it.
//
// Every slot is optional. An empty profile is structural playback.
PlaybackProfile {
    // Each optional, each holding the map it was given.
    var playbackMap, graceMap, controlMap, envelopeMap;

    *new { ^super.new }

    // Answers this, so calls chain. nil clears a slot.
    //
    // >>> PlaybackProfile.new.playbackMap                                -> nil
    // >>> PlaybackProfile.new.graceMap_(PlaybackGraceMap.new).hasLayers  -> true
    playbackMap_ { |map|
        playbackMap = this.prChecked(map, PlaybackMap, "playbackMap");
        ^this
    }

    graceMap_ { |map|
        graceMap = this.prChecked(map, PlaybackGraceMap, "graceMap");
        ^this
    }

    controlMap_ { |map|
        controlMap = this.prChecked(map, PlaybackControlMap, "controlMap");
        ^this
    }

    envelopeMap_ { |map|
        envelopeMap = this.prChecked(map, PlaybackEnvelopeMap, "envelopeMap");
        ^this
    }

    // The maps themselves, not copies. Each map owns its checked
    // setters.
    playbackMap { ^playbackMap }
    graceMap { ^graceMap }
    controlMap { ^controlMap }
    envelopeMap { ^envelopeMap }

    // >>> PlaybackProfile.new.hasLayers   -> false
    hasLayers { ^playbackMap.notNil or: { graceMap.notNil }
        or: { controlMap.notNil } or: { envelopeMap.notNil } }

    // Every extra SC Event key the active layers write.
    //
    // A grace map contributes no keys of its own.
    //
    // Two layers writing one key is refused here.
    //
    // >>> PlaybackProfile.new.carriedKeys   -> [ ]
    // >>> PlaybackProfile.new.controlMap_(PlaybackControlMap.new.panAt(0, 0, 0)).carriedKeys
    // [ pan ]
    carriedKeys {
        var owners = IdentityDictionary.new;
        var out = List.new;
        var clash;

        this.prLayers.do { |entry|
            var name = entry[0];
            entry[1] !? { |map|
                map.carriedKeys.do { |key|
                    var seen = owners[key];
                    if (seen.isNil) {
                        owners[key] = name;
                        out.add(key);
                    } {
                        clash ?? { clash = [key, seen, name] }
                    }
                }
            }
        };
        clash !? { |found| this.prRefuseSharedKey(found) };
        ^out.asArray
    }

    // The key-writing layers, named, in the order they are applied.
    prLayers {
        ^[["playbackMap", playbackMap], ["controlMap", controlMap],
            ["envelopeMap", envelopeMap]]
    }

    // Each layer's own copy, so the two profiles part ways.
    //
    // >>> PlaybackProfile.new.controlMap_(PlaybackControlMap.new.panAt(0, 0, 0))
    //     .copy.controlMap.controlsAt(0, 0)[\pan]
    // 0
    copy {
        ^PlaybackProfile.new
            .playbackMap_(playbackMap !? { |map| map.copy })
            .graceMap_(graceMap !? { |map| map.copy })
            .controlMap_(controlMap !? { |map| map.copy })
            .envelopeMap_(envelopeMap !? { |map| map.copy })
    }

    // The events of `element` with every active layer applied.
    //
    // >>> PlaybackProfile.new.events(Measure("1/4", "c4")).size   -> 1
    events { |element, prepare = true|
        ^this.prEventsOf(Rastrum.prepared(element, prepare))
    }

    // One Ppar over the timelines. `tempo: false` leaves the clock to another
    // source.
    pattern { |element, prepare = true, tempo = true|
        var tree = Rastrum.prepared(element, prepare);
        var music = PatternWriter.pattern(this.prEventsOf(tree),
            this.carriedKeys);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // One Pbind per timeline. No tempo lane here.
    pbinds { |element, prepare = true|
        ^PatternWriter.pbinds(
            this.prEventsOf(Rastrum.prepared(element, prepare)),
            this.carriedKeys)
    }

    // The chain itself, over one prepared tree.
    prEventsOf { |tree|
        // Ask for shared-key refusal before building events.
        var events = this.carriedKeys.size;

        events = if (playbackMap.isNil) {
            Rastrum.events(tree, false)
        } {
            playbackMap.events(tree, false)
        };

        graceMap !? { |map| events = map.spliced(events, tree) };
        controlMap !? { |map| events = map.eventsFrom(events) };
        envelopeMap !? { |map| events = map.eventsFrom(events) };
        ^events
    }

    // Note [One key, one layer]. The message names both layers.
    prRefuseSharedKey { |clash|
        Error("PlaybackProfile: % is written by both % and %. Give one layer a "
            "different key.".format(clash[0], clash[1], clash[2])).throw
    }

    // nil clears a slot. Anything else must match the slot's layer.
    prChecked { |map, class, name|
        if (map.isNil) { ^nil };
        if (map.isKindOf(class).not) {
            Error("PlaybackProfile: % takes %, got %.".format(
                name, class.name, map.class.name)).throw
        };
        ^map
    }
}
