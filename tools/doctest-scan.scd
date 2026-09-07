/*
Finds the worked examples in a source file as [line number, expression, expected answer]
*/

{ |balanced|
    { |path|
        var lines = File.readAllString(path).split($\n);
        var found = [], commentAt;

        // The comment on line i, without its slashes, or nil if that line is code.
        commentAt = { |i|
            var text = (lines[i] ? "").stripWhiteSpace;
            if (text.beginsWith("//")) { text.drop(2).stripWhiteSpace } { nil }
        };

        lines.do { |line, i|
            var body = commentAt.value(i);
            if (body.notNil and: { body.beginsWith(">>>") }) {
                var arrows, cut, expr, want, next, last = i;
                body = body.drop(3).stripWhiteSpace;
                while {
                    var ahead = commentAt.value(last + 1);
                    ahead.notNil
                    and: { ahead.notEmpty }
                    and: { ahead.beginsWith(">>>").not }
                    and: { balanced.value(body).not or: { ahead.beginsWith(".") } }
                } {
                    last = last + 1;
                    body = body ++ " " ++ commentAt.value(last);
                };
                arrows = body.findAll("->") ? [];
                arrows.reverseDo { |at|
                    var left = body.copyRange(0, at - 1);
                    if (cut.isNil and: { balanced.value(left) }) { cut = at };
                };
                if (cut.notNil) {
                    expr = body.copyRange(0, cut - 1);
                    want = body.copyToEnd(cut + 2);
                } {
                    next = commentAt.value(last + 1);
                    expr = body;
                    want = "";
                    if (next.notNil and: {
                        next.notEmpty and: { next.beginsWith(">>>").not }
                    }) {
                        want = next
                    };
                };
                found = found.add(
                    [i + 1, expr.stripWhiteSpace, want.stripWhiteSpace]);
            };
        };
        found
    }
}
