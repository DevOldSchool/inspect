package com.inspect.inspect;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

final class CollapsibleSection extends JPanel
{
	private final JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 3));
	private final JButton heading;
	private final String title;

	CollapsibleSection(String title, boolean collapsed, Consumer<Boolean> onToggle)
	{
		super(new BorderLayout(0, 3));
		this.title = title;
		setOpaque(false);
		body.setOpaque(false);
		heading = new JButton(title);
		heading.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		heading.setForeground(ColorScheme.BRAND_ORANGE);
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setBorder(new EmptyBorder(4, 4, 4, 4));
		heading.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 24, 24));
		heading.setIcon(new Icon()
		{
			@Override
			public void paintIcon(Component component, Graphics graphics, int x, int y)
			{
				graphics.setColor(ColorScheme.BRAND_ORANGE);
				if (body.isVisible())
				{
					graphics.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 2, y + 2, y + 6}, 3);
				}
				else
				{
					graphics.fillPolygon(new int[]{x + 2, x + 2, x + 6}, new int[]{y, y + 8, y + 4}, 3);
				}
			}

			@Override
			public int getIconWidth()
			{
				return 8;
			}

			@Override
			public int getIconHeight()
			{
				return 8;
			}
		});
		heading.addActionListener(event ->
		{
			boolean collapse = body.isVisible();
			setCollapsed(collapse);
			onToggle.accept(collapse);
		});
		add(heading, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		setCollapsed(collapsed);
	}

	void addContent(Component component)
	{
		body.add(component);
	}

	void setCollapsed(boolean collapsed)
	{
		body.setVisible(!collapsed);
		heading.setToolTipText((collapsed ? "Expand " : "Collapse ") + title);
		heading.getAccessibleContext().setAccessibleDescription(collapsed ? "Collapsed" : "Expanded");
		revalidate();
		repaint();
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH - 24, super.getPreferredSize().height);
	}
}
