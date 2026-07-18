#include <stdio.h>
#include <stdlib.h>

#include <glib.h>

#include "NativeVideoPlayer.h"

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s VIDEO_FILE\n", argv[0]);
        return 2;
    }

    VideoPlayer* player = nvp_create();
    if (!player) {
        fprintf(stderr, "failed to create native player\n");
        return 1;
    }
    if (!nvp_open_uri(player, argv[1])) {
        fprintf(stderr, "failed to open synthetic video\n");
        nvp_destroy(player);
        return 1;
    }

    char* decoder = NULL;
    for (int attempt = 0; attempt < 100 && !decoder; attempt++) {
        g_usleep(50 * G_TIME_SPAN_MILLISECOND);
        decoder = nvp_get_video_decoder_name(player);
    }
    if (!decoder || !decoder[0]) {
        fprintf(stderr, "GStreamer did not report the selected video decoder\n");
        free(decoder);
        nvp_destroy(player);
        return 1;
    }

    int32_t decoded_color[7] = {0};
    for (int attempt = 0; attempt < 100; attempt++) {
        nvp_get_decoded_color_info(player, decoded_color);
        if (decoded_color[0] > 0 && decoded_color[1] > 0) break;
        g_usleep(50 * G_TIME_SPAN_MILLISECOND);
    }
    if (decoded_color[0] <= 0 || decoded_color[1] != 8) {
        fprintf(
            stderr,
            "GStreamer decoded color snapshot is missing or has unexpected depth: generation=%d depth=%d\n",
            decoded_color[0],
            decoded_color[1]
        );
        free(decoder);
        nvp_destroy(player);
        return 1;
    }

    printf(
        "GSTREAMER_DECODER_SMOKE_OK %s generation=%d depth=%d transfer=%d\n",
        decoder,
        decoded_color[0],
        decoded_color[1],
        decoded_color[3]
    );
    free(decoder);
    nvp_destroy(player);
    return 0;
}
