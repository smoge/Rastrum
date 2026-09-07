// PitchStream: shared RTM pitch material.
//
// A `Routine` subclass. `next` and `reset` stay the ordinary stream
// API. The class name is the contract: this stream cycles.
//
// Build it with `RhythmTree.pitchStream`.
//
// It yields pitch specs or `pitch -> markings` for marked pitch
// tokens.
//
// >>> RhythmTree.pitchStream("c e").class             -> PitchStream
// >>> RhythmTree.pitchStream("c e").next              -> MusicPitch("c[4]")
// >>> RhythmTree.pitchStream(nil).isKindOf(Routine)   -> true

PitchStream : Routine {

    // Private constructor. Rows are checked before they cycle because
    // drawing is stateful and a refused draw would already have
    // advanced a shared row.
    //
    // >>> PitchStream.prCycling([60, 62]).next   -> 60
    *prCycling { |items, label = "RhythmTree.pitchStream"|
        if (items.isSequenceableCollection.not or: { items.isKindOf(String) }) {
            Error(
                "%: % is not a list of pitch material."
                .format(label, items.asCompileString)
            ).throw
        };
        if (items.isEmpty) {
            Error(
                "%: empty pitch list. Omit it for middle C."
                .format(label)
            ).throw
        };
        items.do { |each| this.prCheckedItem(each, label) };
        ^super.new({ loop { items.do { |each| each.yield } } })
    }

    // One row item: a pitch spec or `pitch -> markings`.
    //
    // Spans stay on the score. Rows cycle.
    *prCheckedItem { |item, label|
        var spec = item, payload = nil;

        if (item.isKindOf(Association)) { spec = item.key; payload = item.value };
        MusicPitch.fromSpec(spec);
        payload !? { payload.do { |mark|
            if (mark.isKindOf(Spanner)) {
                Error(
                    "%: % carries %. Pitch rows cycle; put spans on the score."
                    .format(label, spec, mark)
                ).throw
            };
            if (mark.isKindOf(Marking).not) {
                Error(
                    "%: % carries %. Use a pitch or `pitch -> markings`."
                    .format(label, spec, mark.class)
                ).throw
            }
        } };
        ^item
    }

    // Refused. Plain Routines may end. This stream must not.
    *new { |func, stackSize|
        Error("PitchStream: use RhythmTree.pitchStream(pitches).").throw
    }
}
