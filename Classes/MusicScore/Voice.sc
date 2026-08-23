// An independent timeline inside a bar. Two voices in one measure both start at
// the barline and both last the whole bar. They don't follow one another.
//
// Optional, not implied: a bar with no Voice children is a single
// timeline and answers `[measure]` to `voices`, so everything that
// walks timelines reads both shapes and ordinary music needs no
// special case.
Voice : ScoreContainer {
    var <>name;

    // A timeline is a run of leaves, so `Voice("g2 a2", "upper")` is that run
    // written out. Note [A run of leaves is a run of leaves] in
    // ScoreNotation.sc.
    *new { |children, name|
        ^super.new(ScoreNotation.prChildrenOf(children, "Voice")).initVoice(name)
    }

    initVoice { |argName| name = argName; ^this }

    accept { |writer| ^writer.visitVoice(this) }

    printOn { |stream|
        stream << "Voice(" << children.size;
        if (name.notNil) { stream << ", " << name };
        stream << ")"
    }
}
