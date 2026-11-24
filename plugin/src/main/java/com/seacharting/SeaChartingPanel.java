package com.seacharting;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: form a party (create or join by passphrase), see members, and set the Sailing level
 * used to filter tasks (defaults to your real level; nudge it up for a boost). Flat, dark styling.
 */
@Singleton
public class SeaChartingPanel extends PluginPanel
{
	private final Client client;
	private final PartyService party;
	private final ConfigManager configManager;
	private final SeaChartingConfig config;

	private final JLabel partyStatus = new JLabel("Not in a party");
	private final JTextField passphrase = new JTextField();
	private final JButton createBtn = flat("Create new party");
	private final JButton joinBtn = flat("Join");
	private final JButton leaveBtn = flat("Leave");
	private final JPanel members = new JPanel();
	private final JLabel levelLabel = new JLabel("1", SwingConstants.CENTER);
	private final JButton hideToggle = flat("");
	private final JButton showCompletedToggle = flat("");

	@Inject
	SeaChartingPanel(Client client, PartyService party, ClientThread clientThread, ConfigManager configManager, SeaChartingConfig config)
	{
		this.client = client;
		this.party = party;
		this.configManager = configManager;
		this.config = config;

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(new EmptyBorder(14, 12, 12, 12));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ---- Party ----
		content.add(header("PARTY"));
		content.add(gap(4));
		content.add(note("You and your helper each enter the same passphrase — any two accounts, anywhere."));
		content.add(gap(8));
		partyStatus.setForeground(Color.LIGHT_GRAY);
		partyStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(partyStatus);
		content.add(gap(6));
		passphrase.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		passphrase.setForeground(Color.WHITE);
		passphrase.setCaretColor(Color.WHITE);
		passphrase.setBorder(new EmptyBorder(6, 8, 6, 8));
		full(passphrase, 30);
		passphrase.setToolTipText("Party passphrase");
		content.add(passphrase);
		content.add(gap(6));
		content.add(full(createBtn, 32));
		content.add(gap(5));
		content.add(full(joinBtn, 32));
		content.add(gap(5));
		content.add(full(leaveBtn, 32));
		content.add(gap(10));
		content.add(caption("MEMBERS"));
		content.add(gap(3));
		members.setLayout(new BoxLayout(members, BoxLayout.Y_AXIS));
		members.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		members.setBorder(new EmptyBorder(6, 8, 6, 8));
		members.setAlignmentX(Component.LEFT_ALIGNMENT);
		full(members, 62);
		content.add(members);

		content.add(gap(18));

		// ---- Sailing level ----
		content.add(header("SAILING LEVEL"));
		content.add(gap(6));
		content.add(levelStepper(clientThread));
		content.add(gap(4));
		content.add(note("Your level, used to filter tasks. Nudge it up for a boost; ↺ resets to your real level."));
		content.add(gap(10));
		hideToggle.setHorizontalAlignment(SwingConstants.LEFT);
		hideToggle.setBorder(new EmptyBorder(4, 2, 4, 2));
		hideToggle.addActionListener(e -> {
			configManager.setConfiguration(SeaChartingConfig.GROUP, "hideAboveLevel", !config.hideAboveLevel());
			updateToggle();
		});
		full(hideToggle, 28);
		content.add(hideToggle);
		content.add(gap(4));
		showCompletedToggle.setHorizontalAlignment(SwingConstants.LEFT);
		showCompletedToggle.setBorder(new EmptyBorder(4, 2, 4, 2));
		showCompletedToggle.addActionListener(e -> {
			configManager.setConfiguration(SeaChartingConfig.GROUP, "showCompleted", !config.showCompleted());
			updateToggle();
		});
		full(showCompletedToggle, 28);
		content.add(showCompletedToggle);

		add(content, BorderLayout.NORTH);

		createBtn.addActionListener(e -> clientThread.invoke(() -> party.changeParty(party.generatePassphrase())));
		joinBtn.addActionListener(e -> {
			String p = passphrase.getText().trim();
			if (!p.isEmpty())
			{
				party.changeParty(p);
			}
		});
		leaveBtn.addActionListener(e -> party.changeParty(null));

		updateToggle();
		refresh();
	}

	private JComponent levelStepper(ClientThread clientThread)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		JButton minus = stepBtn("−");
		JButton plus = stepBtn("+");
		JButton reset = stepBtn("↺");
		reset.setToolTipText("Reset to your real level");
		minus.addActionListener(e -> setOverride(Math.max(1, levelValue() - 1)));
		plus.addActionListener(e -> setOverride(Math.min(99, levelValue() + 1)));
		reset.addActionListener(e -> {
			configManager.setConfiguration(SeaChartingConfig.GROUP, "levelOverride", 0);
			updateLevel();
		});

		levelLabel.setFont(FontManager.getRunescapeBoldFont());
		levelLabel.setForeground(Color.WHITE);
		levelLabel.setOpaque(true);
		levelLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		levelLabel.setBorder(new EmptyBorder(0, 6, 0, 6));
		levelLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		levelLabel.setMinimumSize(new Dimension(40, 30));

		p.add(minus);
		p.add(Box.createRigidArea(new Dimension(5, 0)));
		p.add(levelLabel);
		p.add(Box.createRigidArea(new Dimension(5, 0)));
		p.add(plus);
		p.add(Box.createRigidArea(new Dimension(5, 0)));
		p.add(reset);
		return p;
	}

	private int levelValue()
	{
		int ov = config.levelOverride();
		if (ov > 0)
		{
			return ov;
		}
		int real = client.getLocalPlayer() != null ? client.getRealSkillLevel(Skill.SAILING) : 0;
		return real > 0 ? real : 1;
	}

	private void setOverride(int v)
	{
		configManager.setConfiguration(SeaChartingConfig.GROUP, "levelOverride", v);
		updateLevel();
	}

	private void updateLevel()
	{
		boolean manual = config.levelOverride() > 0;
		levelLabel.setText(String.valueOf(levelValue()));
		levelLabel.setForeground(manual ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		levelLabel.setToolTipText(manual ? "Manual override" : "Your real Sailing level");
	}

	private void updateToggle()
	{
		boolean on = config.hideAboveLevel();
		hideToggle.setText((on ? "☑" : "☐") + "   Only show tasks I can do");
		hideToggle.setForeground(on ? ColorScheme.BRAND_ORANGE : Color.LIGHT_GRAY);
		boolean sc = config.showCompleted();
		showCompletedToggle.setText((sc ? "☑" : "☐") + "   Show completed");
		showCompletedToggle.setForeground(sc ? ColorScheme.BRAND_ORANGE : Color.LIGHT_GRAY);
	}

	// ---- styling helpers ----

	private static JButton flat(String text)
	{
		JButton b = new JButton(text);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setFont(FontManager.getRunescapeFont());
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(true);
		b.setBorder(new EmptyBorder(6, 10, 6, 10));
		b.setCursor(new Cursor(Cursor.HAND_CURSOR));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (b.isEnabled())
				{
					b.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return b;
	}

	private static JButton stepBtn(String text)
	{
		JButton b = flat(text);
		b.setFont(FontManager.getRunescapeBoldFont());
		Dimension d = new Dimension(34, 30);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
		return b;
	}

	private static JLabel header(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel caption(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Color.GRAY);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel note(String text)
	{
		JLabel l = new JLabel("<html><div style='width:185px'>" + text + "</div></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Color.GRAY);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static Component gap(int h)
	{
		return Box.createRigidArea(new Dimension(0, h));
	}

	private static <T extends JComponent> T full(T c, int h)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		return c;
	}

	/** Re-read party + level state into the UI. Safe to call from any thread. */
	void refresh()
	{
		SwingUtilities.invokeLater(() -> {
			boolean in = party.isInParty();
			if (in && party.getPartyPassphrase() != null)
			{
				passphrase.setText(party.getPartyPassphrase());
			}
			leaveBtn.setEnabled(in);

			// One row per real person: dedupe by name (leaving + rejoining leaves a ghost under a new member
			// id until it times out) and drop not-yet-named "<unknown>" entries.
			java.util.LinkedHashMap<String, Boolean> byName = new java.util.LinkedHashMap<>();
			String hostName = null;
			long minId = Long.MAX_VALUE;
			for (PartyMember m : party.getMembers())
			{
				if (!m.isLoggedIn())
				{
					continue;   // drop members who've left / timed out / not yet online
				}
				String n = m.getDisplayName();
				if (n == null || n.isEmpty() || n.equals("<unknown>"))
				{
					continue;
				}
				byName.merge(n, true, (a, b) -> true);
				if (m.getMemberId() < minId)
				{
					minId = m.getMemberId();   // lowest member id = host (whose route everyone follows)
					hostName = n;
				}
			}
			final String host = hostName;

			partyStatus.setText(in ? ("In party • " + byName.size() + " member(s)") : "Not in a party");

			members.removeAll();
			if (byName.isEmpty())
			{
				JLabel l = new JLabel(in ? "Waiting for members…" : "No one yet");
				l.setFont(FontManager.getRunescapeSmallFont());
				l.setForeground(Color.GRAY);
				members.add(l);
			}
			else
			{
				for (java.util.Map.Entry<String, Boolean> e : byName.entrySet())
				{
					boolean on = e.getValue();
					boolean crown = e.getKey().equals(host);
					JLabel l = new JLabel((on ? "●  " : "○  ") + e.getKey() + (crown ? "  ★ host" : ""));
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(crown ? ColorScheme.BRAND_ORANGE : (on ? Color.WHITE : Color.GRAY));
					members.add(l);
				}
			}
			members.revalidate();
			members.repaint();

			updateLevel();
		});
	}
}
