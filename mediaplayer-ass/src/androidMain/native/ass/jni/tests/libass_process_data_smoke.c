#include <ass/ass.h>

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    static const char script[] =
        "[Script Info]\n"
        "ScriptType: v4.00+\n"
        "PlayResX: 640\n"
        "PlayResY: 360\n"
        "[V4+ Styles]\n"
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
        "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, "
        "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding\n"
        "Style: Default,sans-serif,32,&H00FFFFFF,&H000000FF,&H00000000,"
        "&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1\n"
        "[Events]\n"
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, "
        "Effect, Text\n"
        "Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,Hello ASS\n";

    assert(ass_library_version() >= 0x01705000);
    ASS_Library *library = ass_library_init();
    assert(library);
    ASS_Track *track = ass_new_track(library);
    assert(track);

    ass_process_data(track, script, (int) strlen(script));
    assert(track->n_events == 1);
    assert(track->events[0].Start == 0);
    assert(track->events[0].Duration == 2000);
    assert(strcmp(track->events[0].Text, "Hello ASS") == 0);

    ass_free_track(track);
    ass_library_done(library);
    puts("libass ass_process_data full-script smoke: PASS");
    return 0;
}
