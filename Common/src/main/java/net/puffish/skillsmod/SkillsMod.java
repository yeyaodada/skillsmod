package net.puffish.skillsmod;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.SkillsAPI;
import net.puffish.skillsmod.api.experience.source.ExperienceSource;
import net.puffish.skillsmod.api.util.Problem;
import net.puffish.skillsmod.api.util.Result;
import net.puffish.skillsmod.calculation.operation.builtin.AttributeOperation;
import net.puffish.skillsmod.calculation.operation.builtin.BlockCondition;
import net.puffish.skillsmod.calculation.operation.builtin.BlockStateCondition;
import net.puffish.skillsmod.calculation.operation.builtin.DamageTypeCondition;
import net.puffish.skillsmod.calculation.operation.builtin.EffectOperation;
import net.puffish.skillsmod.calculation.operation.builtin.EntityTypeCondition;
import net.puffish.skillsmod.calculation.operation.builtin.ItemCondition;
import net.puffish.skillsmod.calculation.operation.builtin.ItemStackCondition;
import net.puffish.skillsmod.calculation.operation.builtin.StatCondition;
import net.puffish.skillsmod.calculation.operation.builtin.StatTypeCondition;
import net.puffish.skillsmod.calculation.operation.builtin.SwitchOperation;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyBlockTagCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyDamageTypeTagCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyEntityTypeTagCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyItemTagCondition;
import net.puffish.skillsmod.commands.CategoryCommand;
import net.puffish.skillsmod.commands.ExperienceCommand;
import net.puffish.skillsmod.commands.OpenCommand;
import net.puffish.skillsmod.commands.PointsCommand;
import net.puffish.skillsmod.commands.SkillsCommand;
import net.puffish.skillsmod.config.CategoryConfig;
import net.puffish.skillsmod.config.ModConfig;
import net.puffish.skillsmod.config.PackConfig;
import net.puffish.skillsmod.config.experience.ExperienceSourceConfig;
import net.puffish.skillsmod.config.reader.ConfigReader;
import net.puffish.skillsmod.config.reader.FileConfigReader;
import net.puffish.skillsmod.config.reader.PackConfigReader;
import net.puffish.skillsmod.config.skill.SkillConfig;
import net.puffish.skillsmod.config.skill.SkillRewardConfig;
import net.puffish.skillsmod.experience.source.builtin.CraftItemExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.EatFoodExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.IncreaseStatExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.KillEntityExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.MineBlockExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.TakeDamageExperienceSource;
import net.puffish.skillsmod.impl.rewards.RewardUpdateContextImpl;
import net.puffish.skillsmod.impl.config.ConfigContextImpl;
import net.puffish.skillsmod.network.Packets;
import net.puffish.skillsmod.reward.builtin.AttributeReward;
import net.puffish.skillsmod.reward.builtin.CommandReward;
import net.puffish.skillsmod.reward.builtin.ScoreboardReward;
import net.puffish.skillsmod.reward.builtin.TagReward;
import net.puffish.skillsmod.server.data.CategoryData;
import net.puffish.skillsmod.server.data.PlayerData;
import net.puffish.skillsmod.server.data.ServerData;
import net.puffish.skillsmod.server.event.ServerEventListener;
import net.puffish.skillsmod.server.event.ServerEventReceiver;
import net.puffish.skillsmod.server.network.ServerPacketSender;
import net.puffish.skillsmod.server.network.packets.in.SkillClickInPacket;
import net.puffish.skillsmod.server.network.packets.out.ExperienceUpdateOutPacket;
import net.puffish.skillsmod.server.network.packets.out.HideCategoryOutPacket;
import net.puffish.skillsmod.server.network.packets.out.OpenScreenOutPacket;
import net.puffish.skillsmod.server.network.packets.out.PointsUpdateOutPacket;
import net.puffish.skillsmod.server.network.packets.out.ShowCategoryOutPacket;
import net.puffish.skillsmod.server.network.packets.out.ShowToastOutPacket;
import net.puffish.skillsmod.server.network.packets.out.SkillUpdateOutPacket;
import net.puffish.skillsmod.server.setup.ServerRegistrar;
import net.puffish.skillsmod.server.setup.SkillsArgumentTypes;
import net.puffish.skillsmod.server.setup.SkillsGameRules;
import net.puffish.skillsmod.util.ChangeListener;
import net.puffish.skillsmod.util.DisposeContext;
import net.puffish.skillsmod.util.PathUtils;
import net.puffish.skillsmod.util.PrefixedLogger;
import net.puffish.skillsmod.util.ToastType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SkillsMod {
	public static final int MIN_CONFIG_VERSION = 1;
	public static final int MAX_CONFIG_VERSION = 2;

	private static SkillsMod instance;

	private final PrefixedLogger logger = new PrefixedLogger(SkillsAPI.MOD_ID);

	private final Path modConfigDir;
	private final ServerPacketSender packetSender;

	private final ChangeListener<Optional<Map<Identifier, CategoryConfig>>> categories = new ChangeListener<>(
			Optional.empty(),
			() -> { }
	);

	private SkillsMod(Path modConfigDir, ServerPacketSender packetSender) {
		this.modConfigDir = modConfigDir;
		this.packetSender = packetSender;
	}

	public static SkillsMod getInstance() {
		return instance;
	}

	public static void setup(
			Path configDir,
			ServerRegistrar registrar,
			ServerEventReceiver eventReceiver,
			ServerPacketSender packetSender
	) {
		Path modConfigDir = configDir.resolve(SkillsAPI.MOD_ID);
		try {
			Files.createDirectories(modConfigDir);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		instance = new SkillsMod(modConfigDir, packetSender);

		registrar.registerInPacket(
				Packets.SKILL_CLICK,
				SkillClickInPacket::read,
				instance::onSkillClickPacket
		);

		registrar.registerOutPacket(Packets.SHOW_CATEGORY);
		registrar.registerOutPacket(Packets.HIDE_CATEGORY);
		registrar.registerOutPacket(Packets.SKILL_UPDATE);
		registrar.registerOutPacket(Packets.POINTS_UPDATE);
		registrar.registerOutPacket(Packets.EXPERIENCE_UPDATE);
		registrar.registerOutPacket(Packets.SHOW_TOAST);
		registrar.registerOutPacket(Packets.OPEN_SCREEN);

		eventReceiver.registerListener(instance.new EventListener());

		SkillsGameRules.register(registrar);
		SkillsArgumentTypes.register(registrar);

		AttributeReward.register();
		CommandReward.register();
		ScoreboardReward.register();
		TagReward.register();

		LegacyBlockTagCondition.register();
		LegacyDamageTypeTagCondition.register();
		LegacyEntityTypeTagCondition.register();
		LegacyItemTagCondition.register();

		AttributeOperation.register();
		BlockCondition.register();
		BlockStateCondition.register();
		DamageTypeCondition.register();
		EffectOperation.register();
		EntityTypeCondition.register();
		ItemCondition.register();
		ItemStackCondition.register();
		StatCondition.register();
		StatTypeCondition.register();
		SwitchOperation.register();

		CraftItemExperienceSource.register();
		EatFoodExperienceSource.register();
		IncreaseStatExperienceSource.register();
		KillEntityExperienceSource.register();
		MineBlockExperienceSource.register();
		TakeDamageExperienceSource.register();
	}

	public static Identifier createIdentifier(String path) {
		return new Identifier(SkillsAPI.MOD_ID, path);
	}

	public static Identifier convertIdentifier(Identifier id) {
		if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
			return createIdentifier(id.getPath());
		}
		return id;
	}

	public static MutableText createTranslatable(String type, String path, Object... args) {
		return Text.stringifiedTranslatable(Util.createTranslationKey(type, createIdentifier(path)), args);
	}

	public PrefixedLogger getLogger() {
		return logger;
	}

	private void copyConfigFromJar() {
		PathUtils.copyFileFromJar(
				Path.of("config", "config.json"),
				modConfigDir.resolve("config.json")
		);
	}

	private void loadModConfig(MinecraftServer server) {
		if (!Files.exists(modConfigDir) || PathUtils.isDirectoryEmpty(modConfigDir)) {
			copyConfigFromJar();
		}

		var reader = new FileConfigReader(modConfigDir);
		var cumulatedMap = new LinkedHashMap<Identifier, CategoryConfig>();

		reader.read(Path.of("config.json"))
				.andThen(ModConfig::parse)
				.andThen(modConfig ->
						loadConfig(reader, modConfig, server)
								.mapSuccess(map -> {
									cumulatedMap.putAll(map);
									return modConfig;
								})
				)
				.ifSuccess(modConfig -> {
					cumulatedMap.putAll(loadPackConfig(modConfig, server));

					categories.set(Optional.of(cumulatedMap), () -> {
						for (var category : cumulatedMap.values()) {
							category.dispose(new DisposeContext(server));
						}
					});
				})
				.ifFailure(problem -> {
					logger.error("Configuration could not be loaded:"
							+ System.lineSeparator()
							+ problem
					);

					categories.set(Optional.empty(), () -> { });
				});
	}

	private Result<Map<Identifier, CategoryConfig>, Problem> loadConfig(ConfigReader reader, ModConfig modConfig, MinecraftServer server) {
		var context = new ConfigContextImpl(server);

		return reader.readCategories(SkillsAPI.MOD_ID, modConfig.getCategories(), context)
				.ifSuccess(map -> {
					if (modConfig.getShowWarnings() && !context.warnings().isEmpty()) {
						logger.warn("Configuration loaded successfully with warning(s):"
								+ System.lineSeparator()
								+ context.warnings().stream().collect(Collectors.joining(System.lineSeparator()))
						);
					} else {
						logger.info("Configuration loaded successfully!");
					}
				});
	}

	private Map<Identifier, CategoryConfig> loadPackConfig(ModConfig modConfig, MinecraftServer server) {
		var context = new ConfigContextImpl(server);
		var cumulatedMap = new LinkedHashMap<Identifier, CategoryConfig>();
		var resourceManager = server.getResourceManager();

		var resources = resourceManager.findResources(
				SkillsAPI.MOD_ID,
				id -> id.getPath().endsWith("config.json")
		);

		for (var entry : resources.entrySet()) {
			var resource = entry.getValue();
			var id = entry.getKey();
			var name = id.getNamespace();
			var reader = new PackConfigReader(resourceManager, name);

			reader.readResource(id, resource)
					.andThen(rootElement -> PackConfig.parse(name, rootElement))
					.andThen(packConfig -> reader.readCategories(name, packConfig.getCategories(), context))
					.ifSuccess(map -> {
						if (modConfig.getShowWarnings() && !context.warnings().isEmpty()) {
							logger.warn("Data pack `" + name + "` loaded successfully with warning(s):"
									+ System.lineSeparator()
									+ context.warnings().stream().collect(Collectors.joining(System.lineSeparator()))
							);
						} else {
							logger.info("Data pack `" + name + "` loaded successfully!");
						}
						cumulatedMap.putAll(map);
					})
					.ifFailure(problem ->
							logger.error("Data pack `" + name + "` could not be loaded:"
									+ System.lineSeparator()
									+ problem
					));
		}

		return cumulatedMap;
	}

	private void onSkillClickPacket(ServerPlayerEntity player, SkillClickInPacket packet) {
		if (player.isSpectator()) {
			return;
		}
		tryUnlockSkill(player, packet.getCategoryId(), packet.getSkillId(), false);
	}

	public void unlockSkill(ServerPlayerEntity player, Identifier categoryId, String skillId) {
		tryUnlockSkill(player, categoryId, skillId, true);
	}

	private void tryUnlockSkill(ServerPlayerEntity player, Identifier categoryId, String skillId, boolean force) {
		getCategory(categoryId).ifPresent(category -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
			if (categoryData.tryUnlockSkill(category, player, skillId, force)) {
				packetSender.send(player, SkillUpdateOutPacket.write(categoryId, skillId, true));
				syncPoints(player, category, categoryData);
			}
		}));
	}

	public void lockSkill(ServerPlayerEntity player, Identifier categoryId, String skillId) {
		getCategory(categoryId).ifPresent(category -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
			categoryData.lockSkill(skillId);
			packetSender.send(player, SkillUpdateOutPacket.write(categoryId, skillId, false));
			syncPoints(player, category, categoryData);
		}));
	}

	public void resetSkills(ServerPlayerEntity player, Identifier categoryId) {
		getCategory(categoryId).ifPresent(category -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
			categoryData.resetSkills();
			applyRewards(player, category, categoryData);
			syncCategory(player, category, categoryData);
		}));
	}

	public void eraseCategory(ServerPlayerEntity player, Identifier categoryId) {
		getCategory(categoryId).ifPresent(category -> {
			var playerData = getPlayerData(player);
			playerData.removeCategoryData(category);

			syncCategory(player, category);
		});
	}

	public void unlockCategory(ServerPlayerEntity player, Identifier categoryId) {
		getCategory(categoryId).ifPresent(category -> {
			var playerData = getPlayerData(player);
			playerData.unlockCategory(category);

			syncCategory(player, category);
		});
	}

	public void lockCategory(ServerPlayerEntity player, Identifier categoryId) {
		getCategory(categoryId).ifPresent(category -> {
			var playerData = getPlayerData(player);
			playerData.lockCategory(category);

			syncCategory(player, category);
		});
	}

	public Optional<Boolean> hasExperience(Identifier categoryId) {
		return getCategory(categoryId).map(category -> category.getExperience().isPresent());
	}

	public void addExperience(ServerPlayerEntity player, Identifier categoryId, int amount) {
		getCategory(categoryId).ifPresent(category -> {
			if (category.getExperience().isEmpty()) {
				return;
			}

			getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
				categoryData.addExperience(amount);

				syncExperience(player, category, categoryData);
				syncPoints(player, category, categoryData);
			});
		});
	}

	public void setExperience(ServerPlayerEntity player, Identifier categoryId, int amount) {
		getCategory(categoryId).ifPresent(category -> {
			if (category.getExperience().isEmpty()) {
				return;
			}

			getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
				categoryData.setEarnedExperience(amount);

				syncExperience(player, category, categoryData);
				syncPoints(player, category, categoryData);
			});
		});
	}

	public Optional<Integer> getExperience(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).flatMap(category -> {
			if (category.getExperience().isEmpty()) {
				return Optional.empty();
			}

			return getCategoryDataIfUnlocked(player, category).map(CategoryData::getEarnedExperience);
		});
	}

	public void addExtraPoints(ServerPlayerEntity player, Identifier categoryId, int count) {
		getCategory(categoryId).ifPresent(category -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
			categoryData.addExtraPoints(count);

			syncPoints(player, category, categoryData);
		}));
	}

	public void setExtraPoints(ServerPlayerEntity player, Identifier categoryId, int count) {
		getCategory(categoryId).ifPresent(category -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
			categoryData.setExtraPoints(count);

			syncPoints(player, category, categoryData);
		}));
	}

	public Optional<Integer> getExtraPoints(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId)
				.flatMap(category -> getCategoryDataIfUnlocked(player, category)
						.map(CategoryData::getExtraPoints)
				);
	}

	public Optional<Integer> getPointsLeft(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getPointsLeft(category);
		});
	}

	public Optional<Integer> getCurrentLevel(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getCurrentLevel(category);
		});
	}

	public Optional<Integer> getCurrentExperience(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getCurrentExperience(category);
		});
	}

	public Optional<Integer> getRequiredExperience(ServerPlayerEntity player, Identifier categoryId, int level) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getRequiredExperience(category, level);
		});
	}

	public Optional<Integer> getRequiredTotalExperience(ServerPlayerEntity player, Identifier categoryId, int level) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getRequiredTotalExperience(category, level);
		});
	}

	public Optional<Skill.State> getSkillState(ServerPlayerEntity player, Identifier categoryId, String skillId) {
		return getCategory(categoryId).flatMap(category -> category.getSkills().getById(skillId)
				.flatMap(skill -> category.getDefinitions().getById(skill.getDefinitionId()).map(definition -> {
					var categoryData = getPlayerData(player).getCategoryData(category);
					return categoryData.getSkillState(category, skill, definition);
				}))
		);
	}

	public Collection<Identifier> getUnlockedCategories(ServerPlayerEntity player) {
		var playerData = getPlayerData(player);

		return getAllCategories()
				.stream()
				.filter(playerData::isCategoryUnlocked)
				.map(CategoryConfig::getId)
				.toList();
	}

	public Collection<Identifier> getCategories(boolean onlyWithExperience) {
		return getAllCategories()
				.stream()
				.filter(category -> !onlyWithExperience || category.getExperience().isPresent())
				.map(CategoryConfig::getId)
				.toList();
	}

	public Optional<Collection<String>> getUnlockedSkills(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).map(category -> {
			var categoryData = getPlayerData(player).getCategoryData(category);
			return categoryData.getUnlockedSkillIds();
		});
	}

	public Optional<Collection<String>> getSkills(Identifier categoryId) {
		return getCategory(categoryId).map(
				category -> category.getSkills()
						.getAll()
						.stream()
						.map(SkillConfig::getId)
						.toList()
		);
	}

	public boolean hasCategory(Identifier categoryId) {
		return getCategory(categoryId).isPresent();
	}

	public boolean hasSkill(Identifier categoryId, String skillId) {
		return getCategory(categoryId)
				.map(category -> category.getSkills().getById(skillId).isPresent())
				.orElse(false);
	}

	private void showCategory(ServerPlayerEntity player, CategoryConfig category, CategoryData categoryData) {
		packetSender.send(player, ShowCategoryOutPacket.write(category, categoryData));
	}

	private void hideCategory(ServerPlayerEntity player, CategoryConfig category) {
		packetSender.send(player, HideCategoryOutPacket.write(category.getId()));
	}

	private void syncPoints(ServerPlayerEntity player, CategoryConfig category, CategoryData categoryData) {
		packetSender.send(player, PointsUpdateOutPacket.write(
				category.getId(),
				categoryData.getSpentPoints(category),
				categoryData.getEarnedPoints(category),
				player.getWorld().getGameRules().getBoolean(SkillsGameRules.ANNOUNCE_NEW_POINTS)
		));
	}

	private void syncExperience(ServerPlayerEntity player, CategoryConfig category, CategoryData categoryData) {
		int level = categoryData.getCurrentLevel(category);
		packetSender.send(player, ExperienceUpdateOutPacket.write(
				category.getId(),
				level,
				categoryData.getCurrentExperience(category),
				categoryData.getRequiredExperience(category, level)
		));
	}

	public void refreshReward(ServerPlayerEntity player, Predicate<SkillRewardConfig> reward) {
		for (CategoryConfig category : getAllCategories()) {
			var categoryData = getPlayerData(player).getCategoryData(category);
			categoryData.refreshReward(category, player, reward);
		}
	}

	public void visitExperienceSources(ServerPlayerEntity player, Function<ExperienceSource, Integer> function) {
		for (CategoryConfig category : getAllCategories()) {
			category.getExperience().ifPresent(experience -> getCategoryDataIfUnlocked(player, category).ifPresent(categoryData -> {
				int amount = 0;

				for (ExperienceSourceConfig experienceSource : experience.getExperienceSources()) {
					amount += function.apply(experienceSource.getInstance());
				}

				if (amount != 0) {
					categoryData.addExperience(amount);

					syncExperience(player, category, categoryData);
					syncPoints(player, category, categoryData);
				}
			}));
		}
	}

	private void applyRewards(ServerPlayerEntity player, CategoryConfig category, CategoryData categoryData) {
		categoryData.applyRewards(category, player);
	}

	private void resetRewards(ServerPlayerEntity player, CategoryConfig category) {
		for (var definition : category.getDefinitions().getAll()) {
			for (var reward : definition.getRewards()) {
				reward.getInstance().update(new RewardUpdateContextImpl(player, 0, false));
			}
		}
	}

	private Optional<CategoryData> getCategoryDataIfUnlocked(ServerPlayerEntity player, CategoryConfig category) {
		return getCategoryDataIfUnlocked(getPlayerData(player), category);
	}

	private Optional<CategoryData> getCategoryDataIfUnlocked(PlayerData playerData, CategoryConfig category) {
		if (playerData.isCategoryUnlocked(category)) {
			return Optional.of(playerData.getCategoryData(category));
		}
		return Optional.empty();
	}

	public Optional<Boolean> isCategoryUnlocked(ServerPlayerEntity player, Identifier categoryId) {
		return getCategory(categoryId).map(category -> getPlayerData(player).isCategoryUnlocked(category));
	}

	private Optional<CategoryConfig> getCategory(Identifier categoryId) {
		return categories.get().flatMap(map -> Optional.ofNullable(map.get(categoryId)));
	}

	private Collection<CategoryConfig> getAllCategories() {
		return categories.get().map(Map::values).orElseGet(Collections::emptyList);
	}

	private void syncCategory(ServerPlayerEntity player, CategoryConfig category, CategoryData categoryData) {
		applyRewards(player, category, categoryData);
		showCategory(player, category, categoryData);
	}

	private void syncCategory(ServerPlayerEntity player, CategoryConfig category) {
		getCategoryDataIfUnlocked(player, category).ifPresentOrElse(
				categoryData -> syncCategory(player, category, categoryData),
				() -> {
					resetRewards(player, category);
					hideCategory(player, category);
				}
		);
	}

	private void syncAllCategories(ServerPlayerEntity player) {
		if (isConfigValid()) {
			var categories = getAllCategories();
			if (categories.isEmpty()) {
				showToast(player, ToastType.MISSING_CONFIG);
			} else {
				for (var category : categories) {
					syncCategory(player, category);
				}
			}
		} else {
			showToast(player, ToastType.INVALID_CONFIG);
		}
	}

	private void showToast(ServerPlayerEntity player, ToastType type) {
		if (isOperatorOrHost(player)) {
			packetSender.send(player, ShowToastOutPacket.write(type));
		}
	}

	public void openScreen(ServerPlayerEntity player, Optional<Identifier> categoryId) {
		packetSender.send(player, OpenScreenOutPacket.write(categoryId));
	}

	private boolean isConfigValid() {
		return categories.get().isPresent();
	}

	private PlayerData getPlayerData(ServerPlayerEntity player) {
		return ServerData.getOrCreate(getPlayerServer(player)).getPlayerData(player);
	}

	private MinecraftServer getPlayerServer(ServerPlayerEntity player) {
		return Objects.requireNonNull(player.getServer());
	}

	private boolean isOperatorOrHost(ServerPlayerEntity player) {
		var server = getPlayerServer(player);
		return server.isHost(player.getGameProfile())
				|| server.getPlayerManager().isOperator(player.getGameProfile());
	}

	private class EventListener implements ServerEventListener {

		@Override
		public void onServerStarting(MinecraftServer server) {
			loadModConfig(server);
		}

		@Override
		public void onServerReload(MinecraftServer server) {
			for (var player : server.getPlayerManager().getPlayerList()) {
				for (var category : getAllCategories()) {
					hideCategory(player, category);
				}
			}

			loadModConfig(server);

			for (var player : server.getPlayerManager().getPlayerList()) {
				syncAllCategories(player);
			}
		}

		@Override
		public void onPlayerJoin(ServerPlayerEntity player) {
			syncAllCategories(player);
		}

		@Override
		public void onCommandsRegister(CommandDispatcher<ServerCommandSource> dispatcher) {
			dispatcher.register(CommandManager.literal(SkillsAPI.MOD_ID)
					.then(CategoryCommand.create())
					.then(SkillsCommand.create())
					.then(PointsCommand.create())
					.then(ExperienceCommand.create())
					.then(OpenCommand.create())
			);
		}
	}
}
