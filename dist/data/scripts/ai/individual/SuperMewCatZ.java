/*
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package ai.individual;

import ai.group_template.L2AttackableAIScript;
import l2server.DatabasePool;
import l2server.gameserver.ThreadPoolManager;
import l2server.gameserver.ai.CtrlIntention;
import l2server.gameserver.datatables.SpawnTable;
import l2server.gameserver.model.L2CharPosition;
import l2server.gameserver.model.L2Spawn;
import l2server.gameserver.model.World;
import l2server.gameserver.model.actor.Npc;
import l2server.gameserver.model.actor.instance.NpcInstance;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.network.clientpackets.Say2;
import l2server.gameserver.network.serverpackets.CreatureSay;
import l2server.gameserver.util.Util;
import l2server.util.Rnd;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class SuperMewCatZ extends L2AttackableAIScript {
	private static final int SUPER_MEW_CAT_Z = 1603;

	public SuperMewCatZ(int id, String name, String descr) {
		super(id, name, descr);

		addSpawnId(SUPER_MEW_CAT_Z); // Super Mew Cat Z

		for (L2Spawn spawn : SpawnTable.getInstance().getSpawnTable()) {
			if (spawn == null) {
				continue;
			}

			if (spawn.getNpcId() == SUPER_MEW_CAT_Z) {
				notifySpawn(spawn.getNpc());
			}
		}
	}

	@Override
	public final String onSpawn(Npc npc) {
		ThreadPoolManager.getInstance().scheduleGeneral(new MoveTheFucker(npc), 3000);

		return super.onSpawn(npc);
	}

	public static void main(String[] args) {
		new SuperMewCatZ(-1, "SuperMewCatZ", "ai");
	}

	private final int moveTheFucker(final Npc npc) {
		final int x = 83420 + Rnd.get(-250, 250);
		final int y = 148376 + Rnd.get(-250, 250);
		final int z = -3406;

		npc.setIsRunning(Rnd.nextBoolean());
		npc.getAI().setIntention(CtrlIntention.AI_INTENTION_MOVE_TO, new L2CharPosition(x, y, z, 0));

		for (Player player : World.getInstance().getAllPlayersArray()) {
			if (player.getHWID().equals("")) {
				continue;
			}

			Connection con = null;

			try {
				con = DatabasePool.getInstance().getConnection();
				PreparedStatement statement = con.prepareStatement("SELECT * FROM newbiehelper_data WHERE account_name = ? OR hardwareId = ?");
				statement.setString(1, player.getAccountName());
				statement.setString(2, player.getHWID());
				ResultSet rset = statement.executeQuery();

				if (rset.next()) {
					continue;
				}

				rset.close();
				statement.close();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				DatabasePool.close(con);
			}

			NpcInstance cat = Util.getNpcCloseTo(1603, player);

			int catObjectId = 0;
			if (cat != null) {
				catObjectId = cat.getObjectId();
			}

			player.sendPacket(new CreatureSay(catObjectId,
					Say2.TELL,
					"Super Mew the Cat Z",
					"Hey! you haven't received Newbie Tickets yet. Come to me in Giran for some free, time limited gears!"));
		}

		return 120000;
	}

	private final class MoveTheFucker implements Runnable {
		private final Npc npc;

		public MoveTheFucker(Npc npc) {
			this.npc = npc;
		}

		@Override
		public void run() {
			int delay = moveTheFucker(npc);

			ThreadPoolManager.getInstance().scheduleGeneral(this, delay);
		}
	}
}
