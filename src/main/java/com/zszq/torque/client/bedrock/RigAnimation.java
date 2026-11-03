package com.zszq.torque.client.bedrock;

/**
 * rig 里的一个语义槽位绑定：哪个动画、用哪种时钟。
 *
 * @param clip  动画名（{@link AnimationLibrary#get(String)} 的键），null 视为空串
 * @param clock 时钟类型，null 视为 {@link AnimationClock#TIME}
 */
public record RigAnimation(String clip, AnimationClock clock) {
    public RigAnimation {
        clip = clip == null ? "" : clip;
        clock = clock == null ? AnimationClock.TIME : clock;
    }

    /** 这个绑定是否指向一个有效动画名。 */
    public boolean hasClip() {
        return !this.clip.isEmpty();
    }
}
