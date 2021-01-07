package com.bbn.voiceanalyzer;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.time.Instant;
import java.util.*;

public class CommandListener extends ListenerAdapter {

    Rethink rethink;
    JSONObject config;

    public CommandListener(Rethink rethink, JSONObject config) {
        this.rethink = rethink;
        this.config = config;
    }

    public String getTime(Long ms) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(ms);
        return String.format("%02d Days %02d:%02d:%02d",
                calendar.get(Calendar.DAY_OF_MONTH) - 1, calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND));
    }

    public long getSum(String[] data, String endtime) {
        long sum = 0;
        if (data == null) return 0;
        if (endtime == null) endtime = String.valueOf(System.currentTimeMillis());
        for (String dat : data) {
            if (dat.endsWith("-")) dat += endtime;
            sum += Long.parseLong(dat.split("-")[1]) - Long.parseLong(dat.split("-")[0]);
        }
        return sum;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getMessage().getContentRaw().equals("+statstop")) {
            event.getGuild().loadMembers().onSuccess(members -> {
                JSONArray data = new JSONArray();
                HashMap<Long, String> timetoid = new HashMap<>();

                // Get all voice times
                for (Member member : members) {
                    JSONObject memberjson = rethink.getMember(member.getId(), member.getGuild().getId());
                    if (!memberjson.getString("conversations").equals("[]")) {
                        JSONArray conversations = new JSONArray(memberjson.getString("conversations"));
                        long time = 0L;
                        for (Object conversationobj : conversations) {
                            Conversation conversation = new Conversation((JSONObject) conversationobj);
                            time += (Long.parseLong(conversation.getEndTime()) - Long.parseLong(conversation.getStartTime()));
                            time -= getSum(conversation.getMuteTimes(), conversation.getEndTime());
                            time -= getSum(conversation.getIdleTimes(), conversation.getEndTime());
                            time -= getSum(conversation.getDeafTimes(), conversation.getEndTime());
                            time -= getSum(conversation.getSleepTimes(), conversation.getEndTime());
                        }
                        timetoid.put(time, member.getId());
                    }
                }
                // Sort and reverse the list
                Set<Map.Entry<Long, String>> set = timetoid.entrySet();
                List<Map.Entry<Long, String>> list = new ArrayList<>(set);
                list.sort((Map.Entry.comparingByKey()));
                Collections.reverse(list);

                // Build outputstring, Build data object
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<Long, String> entry : list) {
                    if (list.indexOf(entry) < 10) {
                        Member member = event.getGuild().getMemberById(entry.getValue());
                        JSONObject memberjson = rethink.getMember(member.getId(), member.getGuild().getId());
                        data.put(memberjson.put("Tag", member.getUser().getAsTag()));
                        sb.append((list.indexOf(entry) + 1)).append(". ").append(member.getUser().getAsTag()).append(" - ").append(getTime(entry.getKey())).append("\n");
                    }
                }

                // Draw Plot, Save it
                new PlotCreator().createStatstop(data);

                // Send Plot from file in storagechannel, Send final message
                event.getGuild().getTextChannelById(config.getString("storagechannel")).sendFile(new File("./Chart.png")).queue(
                        msg -> event.getTextChannel().sendMessage(
                                new EmbedBuilder()
                                        .setTitle("Statstop")
                                        .setDescription(sb.toString())
                                        .setAuthor(event.getAuthor().getAsTag(), event.getAuthor().getEffectiveAvatarUrl(), event.getAuthor().getEffectiveAvatarUrl())
                                        .setImage(msg.getAttachments().get(0).getUrl())
                                        .setTimestamp(Instant.now())
                                        .build()
                        ).queue()
                );
            });
        } else if (event.getMessage().getContentRaw().startsWith("+stats")) {
            // Get Member
            Member member = event.getMember();
            if (event.getMessage().getMentionedMembers().size() == 1)
                member = event.getMessage().getMentionedMembers().get(0);

            // Get Conversation Object
            JSONArray conversations = new JSONArray(rethink.getMember(member.getId(), event.getGuild().getId()).getString("conversations"));

            // Get Field Values
            long connected = 0;
            long muted = 0;
            long deafed = 0;
            long idle = 0;
            long sleep = 0;
            for (Object conversationobj : conversations) {
                Conversation conversation = new Conversation((JSONObject) conversationobj);
                connected += (double) (Long.parseLong(conversation.getEndTime()) - Long.parseLong(conversation.getStartTime()));
                muted += getSum(conversation.getMuteTimes(), conversation.getEndTime());
                deafed += getSum(conversation.getDeafTimes(), conversation.getEndTime());
                idle += getSum(conversation.getIdleTimes(), conversation.getEndTime());
                sleep += getSum(conversation.getSleepTimes(), conversation.getEndTime());
            }

            long total = connected - muted - deafed - idle - sleep;

            // Draw Plot, Save it
            new PlotCreator().createStat(conversations);

            // Send Plot from file in storagechannel, Send final message
            Member finalMember = member;
            long finalConnected = connected;
            long finalMuted = muted;
            long finalIdle = idle;
            long finalDeafed = deafed;
            long finalSleep = sleep;
            event.getGuild().getTextChannelById(config.getString("storagechannel")).sendFile(new File("./Chart.png")).queue(
                    msg -> event.getTextChannel().sendMessage(
                            new EmbedBuilder()
                                    .setTitle("Stats")
                                    .setAuthor(finalMember.getUser().getAsTag(), finalMember.getUser().getEffectiveAvatarUrl(), finalMember.getUser().getEffectiveAvatarUrl())
                                    .addField("Conversations", String.valueOf(conversations.length()), true)
                                    .addField("Time", getTime(finalConnected), true)
                                    .addField("Muted", getTime(finalMuted), true)
                                    .addField("Deafened", getTime(finalDeafed), true)
                                    .addField("Idle", getTime(finalIdle), true)
                                    .addField("Sleep", getTime(finalSleep), true)
                                    .addField("Total", getTime(total), true)
                                    .setImage(msg.getAttachments().get(0).getUrl())
                                    .setTimestamp(Instant.now())
                                    .build()
                    ).queue()
            );
        }
    }
}
