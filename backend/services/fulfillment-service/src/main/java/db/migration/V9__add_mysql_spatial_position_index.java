package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.SQLException;
import java.sql.Statement;

public class V9__add_mysql_spatial_position_index extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        String databaseProduct = context.getConnection().getMetaData().getDatabaseProductName();
        if (!"MySQL".equalsIgnoreCase(databaseProduct)) {
            return;
        }
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    ALTER TABLE shipment_latest_position
                    ADD COLUMN coordinates POINT SRID 4326 NOT NULL
                    AFTER latitude
                    """);
            statement.execute("""
                    CREATE SPATIAL INDEX idx_shipment_latest_position_coordinates
                    ON shipment_latest_position (coordinates)
                    """);
        }
    }
}
