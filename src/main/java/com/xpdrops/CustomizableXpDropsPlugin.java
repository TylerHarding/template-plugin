package com.xpdrops;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import static net.runelite.api.ScriptID.XPDROPS_SETDROPSIZE;

@PluginDescriptor(
	name = "Customizable XP drops",
	description = "Allows one to use fully customizable xp drops independent of the in-game ones"
)
@Slf4j
public class CustomizableXpDropsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private XpDropOverlay xpDropOverlay;

	@Inject
	private XpDropOverlayActor xpDropOverlayActor;

	@Inject
	private XpDropsConfig config;

	@Inject
	private  ClientThread clientThread;

	@Provides
	XpDropsConfig provideConfig(ConfigManager configManager)
	{
		return (XpDropsConfig)configManager.getConfig(XpDropsConfig.class);
	}

	int skillPriorityComparator(XpDrop x1, XpDrop x2)
	{
		int priority1 = XpDropOverlay.SKILL_PRIORITY[x1.getSkill().ordinal()];
		int priority2 = XpDropOverlay.SKILL_PRIORITY[x2.getSkill().ordinal()];
		return Integer.compare(priority1, priority2);
	}

	@Getter
	private final PriorityQueue<XpDrop> queue = new PriorityQueue<>(this::skillPriorityComparator);
	private final HashSet<String> filteredSkills = new HashSet<>();
	private static final int[] previous_exp = new int[Skill.values().length - 1];
	private static final int[] SKILL_ICON_ORDINAL_ICONS = new int[]{197, 199, 198, 203, 200, 201, 202, 212, 214, 208,
		211, 213, 207, 210, 209, 205, 204, 206, 216, 217, 215, 220, 221};

	private XpDropOverlay currentOverlay;

	@Override
	protected void startUp()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				int[] xps = client.getSkillExperiences();
				System.arraycopy(xps, 0, previous_exp, 0, previous_exp.length);
			});
		}
		else
		{
			Arrays.fill(previous_exp, 0);
		}
		queue.clear();
		if (config.attachToPlayer())
		{
			currentOverlay = xpDropOverlayActor;
		}
		else
		{
			currentOverlay = xpDropOverlay;
		}
		overlayManager.add(currentOverlay);
	}

	@Override
	protected void shutDown()
	{
		if (currentOverlay != null)
		{
			overlayManager.remove(currentOverlay);
		}
	}

	@Subscribe
	protected void onConfigChanged(ConfigChanged configChanged)
	{
		if ("CustomizableXPDrops".equals(configChanged.getGroup()))
		{
			if ("attachToPlayer".equals(configChanged.getKey()))
			{
				if (config.attachToPlayer() && currentOverlay != xpDropOverlayActor)
				{
					overlayManager.remove(currentOverlay);
					currentOverlay = xpDropOverlayActor;
					overlayManager.add(currentOverlay);
				}
				else if (!config.attachToPlayer() && currentOverlay == xpDropOverlayActor)
				{
					overlayManager.remove(currentOverlay);
					currentOverlay = xpDropOverlay;
					overlayManager.add(currentOverlay);
				}
			}
			if ("skillsToFilter".equals(configChanged.getKey()))
			{
				filteredSkills.clear();
				filteredSkills.addAll(Text.fromCSV(config.skillsToFilter()).stream().map(String::toLowerCase).collect(Collectors.toList()));
				// Since most people know this skill by runecrafting not runecraft
				if (filteredSkills.contains("runecrafting"))
				{
					filteredSkills.add("runecraft");
				}
			}
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		if (scriptPreFired.getScriptId() == XPDROPS_SETDROPSIZE)
		{
			final int[] intStack = client.getIntStack();
			final int intStackSize = client.getIntStackSize();
			// This runs prior to the proc being invoked, so the arguments are still on the stack.
			// Grab the first argument to the script.
			final int widgetId = intStack[intStackSize - 4];

			final Widget xpdrop = client.getWidget(widgetId);
			if (xpdrop != null)
			{
				xpdrop.setHidden(true);
			}
		}
	}

	@Subscribe
	protected void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN || gameStateChanged.getGameState() == GameState.HOPPING)
		{
			Arrays.fill(previous_exp, 0);
		}
	}

	@Subscribe
	protected void onFakeXpDrop(FakeXpDrop event)
	{
		int currentXp = event.getXp();
		if (event.getXp() >= 20000000)
		{
			// fake fake xp drop?
			return;
		}
		if (filteredSkills.contains(event.getSkill().getName().toLowerCase()))
		{
			return;
		}

		XpDrop xpDrop = new XpDrop(event.getSkill(), currentXp, matchPrayerStyle(event.getSkill()), true);
		queue.add(xpDrop);
	}

	@Subscribe
	protected void onStatChanged(StatChanged event)
	{
		int currentXp = event.getXp();
		int previousXp = previous_exp[event.getSkill().ordinal()];
		if (previousXp > 0 && currentXp - previousXp > 0 && !filteredSkills.contains(event.getSkill().getName().toLowerCase()))
		{
			XpDrop xpDrop = new XpDrop(event.getSkill(), currentXp - previousXp, matchPrayerStyle(event.getSkill()), false);
			queue.add(xpDrop);
		}

		previous_exp[event.getSkill().ordinal()] = event.getXp();
	}

	protected BufferedImage getSkillIcon(Skill skill)
	{
		int index = skill.ordinal();
		int icon = SKILL_ICON_ORDINAL_ICONS[index];
		return getIcon(icon, 0);
	}

	protected BufferedImage getIcon(int icon, int spriteIndex)
	{
		if (client == null)
		{
			return null;
		}
		SpritePixels[] pixels = client.getSprites(client.getIndexSprites(), icon, 0);
		if (pixels != null && pixels.length >= spriteIndex + 1 && pixels[spriteIndex] != null)
		{
			return pixels[spriteIndex].toBufferedImage();
		}
		return null;
	}

	private XpDropStyle getActivePrayerType()
	{
		for (XpPrayer prayer : XpPrayer.values())
		{
			if (client.isPrayerActive(prayer.getPrayer()))
			{
				return prayer.getType();
			}
		}
		return null;
	}

	protected XpDropStyle matchPrayerStyle(Skill skill)
	{
		XpDropStyle style = XpDropStyle.DEFAULT;
		XpDropStyle active = getActivePrayerType();
		switch (skill)
		{
			case MAGIC:
				if (active == XpDropStyle.MAGE)
				{
					style = active;
				}
				break;
			case RANGED:
				if (active == XpDropStyle.RANGE)
				{
					style = active;
				}
				break;
			case ATTACK:
			case STRENGTH:
			case DEFENCE:
				if (active == XpDropStyle.MELEE)
				{
					style = active;
				}
				break;
		}
		return style;
	}
}