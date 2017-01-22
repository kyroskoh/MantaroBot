package net.kodehawa.mantarobot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.kodehawa.mantarobot.util.Utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Scheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private final BlockingQueue<AudioTrack> queue;
    private final MessageReceivedEvent _event;

    public Scheduler(MessageReceivedEvent event, AudioPlayer player) {
        _event = event;
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
    }

    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        }
    }

    public void nextTrack() {
        player.startTrack(queue.poll(), false);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            nextTrack();
            _event.getChannel().sendMessage(":mega: Now playing ->``" + getPlayer().getPlayingTrack().getInfo().title
                    + " (" + Utils.instance().getDurationMinutes(getPlayer().getPlayingTrack().getInfo().length) + ")``").queue();
        }
    }

    public AudioPlayer getPlayer(){
            return player;
    }

    public int getQueueSize(){
        return queue.size();
    }

    public BlockingQueue<AudioTrack> getQueue(){
        return queue;
    }

    public String getQueueList(){
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for(AudioTrack audioTrack : queue){
            long aDuration = audioTrack.getDuration();
            String duration = String.format("%02d:%02d",
                    TimeUnit.MILLISECONDS.toMinutes(aDuration),
                    TimeUnit.MILLISECONDS.toSeconds(aDuration) -  TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(aDuration))
            );

            sb.append("[").append(n)
                    .append("] ")
                    .append(audioTrack.getInfo().title)
                    .append(" **(")
                    .append(duration)
                    .append(")**")
                    .append("\n"
                    );
            n++;
        }
        return sb.toString();
    }
}