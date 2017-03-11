package net.kodehawa.mantarobot;

import br.com.brjdevs.java.utils.Holder;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.rethinkdb.RethinkDB;
import com.rethinkdb.net.Connection;
import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.JDAInfo;
import net.dv8tion.jda.core.entities.*;
import net.kodehawa.mantarobot.commands.music.MantaroAudioManager;
import net.kodehawa.mantarobot.commands.music.VoiceChannelListener;
import net.kodehawa.mantarobot.core.LoadState;
import net.kodehawa.mantarobot.core.listeners.MantaroListener;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.Data;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.log.DiscordLogBack;
import net.kodehawa.mantarobot.log.SimpleLogToSLF4JAdapter;
import net.kodehawa.mantarobot.modules.Module;
import net.kodehawa.mantarobot.utils.Async;
import net.kodehawa.mantarobot.utils.ThreadPoolHelper;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static net.kodehawa.mantarobot.MantaroInfo.VERSION;
import static net.kodehawa.mantarobot.core.LoadState.*;

public class MantaroBot {
	private static MantaroBot instance;
	private static final Logger LOGGER = LoggerFactory.getLogger("MantaroBot");
	private MantaroShard[] shards;
	private MantaroAudioManager audioManager;
	private LoadState status = PRELOAD;
	private int totalShards;
	private static final RethinkDB database = RethinkDB.r;
	//TODO actual db
	//private static final Connection conn = database.connection().hostname("localhost").port(28015).connect();

	public MantaroAudioManager getAudioManager() {
		return audioManager;
	}

	public MantaroShard[] getShards() {
		return shards;
	}
	public MantaroShard getShard(JDA jda) {
		if (jda.getShardInfo() == null) return shards[0];
		return Arrays.stream(shards).filter(shard -> shard.getId() == jda.getShardInfo().getShardId()).findFirst().orElse(null);
	}

	public MantaroShard getShard(int id) {
		return Arrays.stream(shards).filter(shard -> shard.getId() == id).findFirst().orElse(null);
	}

	public int getId(JDA jda) {
		return jda.getShardInfo() == null ? 0 : jda.getShardInfo().getShardId();
	}

	public MantaroShard getShard(long guildId) {
		return getShard((int) ((guildId >> 22) % totalShards));
	}

	public LoadState getStatus() {
		return status;
	}

	private void init() throws Exception {
		SimpleLogToSLF4JAdapter.install();
		LOGGER.info("MantaroBot starting...");

		Config config = MantaroData.getConfig().get();

		Future<Set<Class<? extends Module>>> classesAsync = ThreadPoolHelper.defaultPool().getThreadPool()
				.submit(() -> new Reflections("net.kodehawa.mantarobot.commands").getSubTypesOf(Module.class));

		totalShards = getRecommendedShards(config);
		shards = new MantaroShard[totalShards];
		status = LOADING;
		for (int i = 0; i < totalShards; i++) {
			LOGGER.info("Starting shard #" + i + " of " + totalShards);
			shards[i] = new MantaroShard(i, totalShards);
			LOGGER.info("Finished loading shard #" + i + ".");
			Thread.sleep(5_000L);
		}
		DiscordLogBack.enable();
		status = LOADED;
		LOGGER.info("[-=-=-=-=-=- MANTARO STARTED -=-=-=-=-=-]");
		LOGGER.info("Started bot instance.");
		LOGGER.info("Started MantaroBot " + VERSION + " on JDA " + JDAInfo.VERSION);
		//LOGGER.info("Started RethinkDB on " + conn.hostname + " successfully.");
		Data data = MantaroData.getData().get();
		Random r = new Random();
		audioManager = new MantaroAudioManager();
		List<String> splashes = MantaroData.getSplashes().get();
		if (splashes.removeIf(s -> s == null || s.isEmpty())) MantaroData.getSplashes().save();

		Runnable changeStatus = () -> {
			String newStatus = splashes.get(r.nextInt(splashes.size()));
			Arrays.stream(shards).forEach(shard -> shard.getJDA().getPresence().setGame(Game.of(data.defaultPrefix + "help | " + newStatus + " | [" + shard.getId() + "]")));
			LOGGER.info("Changed status to: " + newStatus);
		};

		changeStatus.run();

		Arrays.stream(shards).forEach(MantaroShard::updateServerCount);

		Async.startAsyncTask("Splash Thread", changeStatus, 600);


		MantaroData.getConfig().save();

		Set<Module> modules = new HashSet<>();
		for (Class<? extends Module> c : classesAsync.get()) {
			try {
				modules.add(c.newInstance());
			} catch (InstantiationException e) {
				LOGGER.error("Cannot initialize a command", e);
			} catch (IllegalAccessException e) {
				LOGGER.error("Cannot access a command class!", e);
			}
		}

		status = POSTLOAD;
		LOGGER.info("Finished loading basic components. Status is now set to POSTLOAD");
		LOGGER.info("Loaded " + Module.Manager.commands.size() + " commands in " + totalShards + " shards.");

		modules.forEach(Module::onPostLoad);
	}


	private int getRecommendedShards(Config config) {
		try {
			HttpResponse<JsonNode> shards = Unirest.get("https://discordapp.com/api/gateway/bot")
					.header("Authorization", "Bot " + config.token)
					.header("Content-Type", "application/json")
					.asJson();
			return shards.getBody().getObject().getInt("shards");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 1;
	}

	public List<Guild> getGuilds() {
		return Arrays.stream(shards).map(bot -> bot.getJDA().getGuilds()).flatMap(List::stream).collect(Collectors.toList());
	}

	public List<User> getUsers() {
		return Arrays.stream(shards).map(bot -> bot.getJDA().getUsers()).flatMap(List::stream).collect(Collectors.toList());
	}

	public User getUserById(String id) {
		return Arrays.stream(shards).map(shard -> shard.getJDA().getUserById(id)).filter(Objects::nonNull).findFirst().orElse(null);
	}

	public List<TextChannel> getTextChannels() {
		return Arrays.stream(shards).map(bot -> bot.getJDA().getTextChannels()).flatMap(List::stream).collect(Collectors.toList());
	}

	public List<VoiceChannel> getVoiceChannels() {
		return Arrays.stream(shards).map(bot -> bot.getJDA().getVoiceChannels()).flatMap(List::stream).collect(Collectors.toList());
	}

	public long getResponseTotal() {
		return Arrays.stream(shards).map(bot -> bot.getJDA().getResponseTotal()).mapToLong(Long::longValue).sum();
	}

	public static void main(String[] args) {
		try {
			instance = new MantaroBot();
			instance.init();
		} catch (Exception e) {
			DiscordLogBack.disable();
			LOGGER.error("Could not complete Main Thread Routine!", e);
			LOGGER.error("Cannot continue! Exiting program...");
			System.exit(-1);
		}
	}

	public static MantaroBot getInstance() {
		return instance;
	}
	public static RethinkDB database(){
		return database;
	}

	/*public static Connection databaseConnection(){
		return conn;
	}*/
}
