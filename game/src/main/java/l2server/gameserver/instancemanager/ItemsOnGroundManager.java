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

package l2server.gameserver.instancemanager;

import l2server.Config;
import l2server.DatabasePool;
import l2server.gameserver.ItemsAutoDestroy;
import l2server.gameserver.ThreadPoolManager;
import l2server.gameserver.model.Item;
import l2server.gameserver.model.World;
import l2server.gameserver.templates.item.EtcItemType;
import l2server.util.loader.annotations.Load;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * This class manage all items on ground
 *
 * @author DiezelMax - original idea
 * @author Enforcer  - actual build
 * @version $Revision: $ $Date: $
 */
public class ItemsOnGroundManager {
	private static Logger log = LoggerFactory.getLogger(ItemsOnGroundManager.class.getName());


	protected List<Item> items = new ArrayList<>();
	private final StoreInDb task = new StoreInDb();

	private ItemsOnGroundManager() {
	}

	public static ItemsOnGroundManager getInstance() {
		return SingletonHolder.instance;
	}

	@Load
	public void load() {
		// If SaveDroppedItem is false, may want to delete all items previously stored to avoid add old items on reactivate
		if (!Config.SAVE_DROPPED_ITEM && Config.CLEAR_DROPPED_ITEM_TABLE) {
			emptyTable();
		}
		
		if (Config.SAVE_DROPPED_ITEM_INTERVAL > 0) {
			ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(task, Config.SAVE_DROPPED_ITEM_INTERVAL, Config.SAVE_DROPPED_ITEM_INTERVAL);
		}

		if (!Config.SAVE_DROPPED_ITEM) {
			return;
		}

		// if DestroyPlayerDroppedItem was previously  false, items curently protected will be added to ItemsAutoDestroy
		if (Config.DESTROY_DROPPED_PLAYER_ITEM) {
			Connection con = null;
			try {
				String str = null;
				if (!Config.DESTROY_EQUIPABLE_PLAYER_ITEM) //Recycle misc. items only
				{
					str = "UPDATE itemsonground SET drop_time=? WHERE drop_time=-1 AND equipable=0";
				} else if (Config.DESTROY_EQUIPABLE_PLAYER_ITEM) //Recycle all items including equip-able
				{
					str = "UPDATE itemsonground SET drop_time=? WHERE drop_time=-1";
				}
				con = DatabasePool.getInstance().getConnection();
				PreparedStatement statement = con.prepareStatement(str);
				statement.setLong(1, System.currentTimeMillis());
				statement.execute();
				statement.close();
			} catch (Exception e) {
				log.error("Error while updating table ItemsOnGround " + e.getMessage(), e);
			} finally {
				DatabasePool.close(con);
			}
		}

		//Add items to world
		Connection con = null;
		Item item;
		try {
			con = DatabasePool.getInstance().getConnection();
			Statement s = con.createStatement();
			ResultSet result;
			int count = 0;
			result = s.executeQuery("SELECT object_id,item_id,count,enchant_level,x,y,z,drop_time,equipable FROM itemsonground");
			while (result.next()) {
				item = new Item(result.getInt(1), result.getInt(2));
				World.getInstance().storeObject(item);
				if (item.isStackable() && result.getInt(3) > 1) //this check and..
				{
					item.setCount(result.getInt(3));
				}
				if (result.getInt(4) > 0) // this, are really necessary?
				{
					item.setEnchantLevel(result.getInt(4));
				}
				item.getPosition().setWorldPosition(result.getInt(5), result.getInt(6), result.getInt(7));
				item.getPosition().setWorldRegion(World.getInstance().getRegion(item.getPosition().getWorldPosition()));
				item.getPosition().getWorldRegion().addVisibleObject(item);
				item.setDropTime(result.getLong(8));
				item.setProtected(result.getLong(8) == -1);
				item.setIsVisible(true);
				World.getInstance().addVisibleObject(item, item.getPosition().getWorldRegion());
				items.add(item);
				count++;
				// add to ItemsAutoDestroy only items not protected
				if (!Config.LIST_PROTECTED_ITEMS.contains(item.getItemId())) {
					if (result.getLong(8) > -1) {
						if (Config.AUTODESTROY_ITEM_AFTER * 1000 > 0 && item.getItemType() != EtcItemType.HERB ||
								Config.HERB_AUTO_DESTROY_TIME * 1000 > 0 && item.getItemType() == EtcItemType.HERB) {
							ItemsAutoDestroy.getInstance().addItem(item);
						}
					}
				}
			}
			result.close();
			s.close();
			if (count > 0) {
				log.info("ItemsOnGroundManager: restored " + count + " items.");
			} else {
				log.info("Initializing ItemsOnGroundManager.");
			}
		} catch (Exception e) {
			log.error("Error while loading ItemsOnGround " + e.getMessage(), e);
		} finally {
			DatabasePool.close(con);
		}
		if (Config.EMPTY_DROPPED_ITEM_TABLE_AFTER_LOAD) {
			emptyTable();
		}
	}

	public void save(Item item) {
		if (!Config.SAVE_DROPPED_ITEM) {
			return;
		}
		items.add(item);
	}

	public void removeObject(Item item) {
		if (Config.SAVE_DROPPED_ITEM && items != null) {
			items.remove(item);
		}
	}

	public void saveInDb() {
		task.run();
	}

	public void cleanUp() {
		items.clear();
	}

	public void emptyTable() {
		Connection conn = null;
		try {
			conn = DatabasePool.getInstance().getConnection();
			PreparedStatement del = conn.prepareStatement("DELETE FROM itemsonground");
			del.execute();
			del.close();
		} catch (Exception e1) {
			log.error("Error while cleaning table ItemsOnGround " + e1.getMessage(), e1);
		} finally {
			DatabasePool.close(conn);
		}
	}

	protected class StoreInDb extends Thread {
		@Override
		public synchronized void run() {
			if (!Config.SAVE_DROPPED_ITEM) {
				return;
			}

			emptyTable();

			if (items.isEmpty()) {
				if (Config.DEBUG) {
					log.warn("ItemsOnGroundManager: nothing to save...");
				}
				return;
			}

			Connection con = null;
			PreparedStatement statement = null;

			try {
				con = DatabasePool.getInstance().getConnection();
				statement = con.prepareStatement(
						"INSERT INTO itemsonground(object_id,item_id,count,enchant_level,x,y,z,drop_time,equipable) VALUES(?,?,?,?,?,?,?,?,?)");

				for (Item item : items) {
					if (item == null) {
						continue;
					}

					if (CursedWeaponsManager.getInstance().isCursed(item.getItemId())) {
						continue; // Cursed Items not saved to ground, prevent double save
					}

					try {
						statement.setInt(1, item.getObjectId());
						statement.setInt(2, item.getItemId());
						statement.setLong(3, item.getCount());
						statement.setInt(4, item.getEnchantLevel());
						statement.setInt(5, item.getX());
						statement.setInt(6, item.getY());
						statement.setInt(7, item.getZ());

						if (item.isProtected()) {
							statement.setLong(8, -1); //item will be protected
						} else {
							statement.setLong(8, item.getDropTime()); //item will be added to ItemsAutoDestroy
						}
						if (item.isEquipable()) {
							statement.setLong(9, 1); //set equip-able
						} else {
							statement.setLong(9, 0);
						}
						statement.execute();
						statement.clearParameters();
					} catch (Exception e) {
						log.error("Error while inserting into table ItemsOnGround: " + e.getMessage(), e);
					}
				}
				statement.close();
			} catch (SQLException e) {
				log.error("SQL error while storing items on ground: " + e.getMessage(), e);
			} finally {
				DatabasePool.close(con);
			}

			if (Config.DEBUG) {
				log.warn("ItemsOnGroundManager: " + items.size() + " items on ground saved");
			}
		}
	}

	@SuppressWarnings("synthetic-access")
	private static class SingletonHolder {
		protected static final ItemsOnGroundManager instance = new ItemsOnGroundManager();
	}
}
