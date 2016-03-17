# Setting up the geOrchestra database

The "georchestra" database hosts several schemas, which are specific to the deployed modules:
```
createuser -SDRI www-data
createdb -E UTF8 -T template0 -O www-data georchestra
psql -d georchestra -c "ALTER USER \"www-data\" WITH PASSWORD 'www-data';"
```

Note 1: It is of course possible to store webapp-specific schemas in separate databases, taking advantage of geOrchestra's extreme configurability.

Note 2: PostGIS extensions are not required in the georchestra database, unless GeoFence is deployed (see below), or ```shared.psql.jdbc.driver=org.postgis.DriverWrapper``` in your configuration (but this is not the default setup).

## Viewer schema

If **mapfishapp** is deployed:
```
psql -d georchestra -f postgresql/02-mapfishapp.sql
```

## Ldapadmin schema

If the **ldapadmin** webapp is deployed:
```
psql -d georchestra -f postgresql/04-ldapadmin.sql
```

## GeoFence schema

If **geofence** is deployed: (make sure to set the correct values for the ```baseURL```, ```username``` and ```password``` fields in the ```geofence.gf_gsinstance``` table)
```
psql -d georchestra -c 'CREATE EXTENSION postgis;'
wget --no-check-certificate https://raw.githubusercontent.com/georchestra/geofence/georchestra-15.06/doc/setup/sql/002_create_schema_postgres.sql -O /tmp/geofence.sql
psql -d georchestra -f /tmp/geofence.sql
```
in the next query, replace every '@...@' with the values of your shared.maven.filters!
```
psql -d georchestra -c "INSERT INTO geofence.gf_gsinstance (id, baseURL, dateCreation, description, name, password, username) values (0, 'http(s)://@shared.server.name@/geoserver', 'now', 'locale geoserver', 'default-gs', '@shared.privileged.geoserver.pass@', '@shared.privileged.geoserver.user@');"
```

## Download form schema

If the **downloadform** module is deployed and ```shared.download_form.activated``` is true in your setup (false by default):
```
psql -d georchestra -f postgresql/03-downloadform.sql
```

## OGC statistics schema

If the **security proxy** is deployed and ```shared.ogc.statistics.activated``` is true in your setup (false by default):
```
psql -d georchestra -f postgresql/05-ogc-server-statistics.sql
```

## Change ownership of database objects
 
Ensure geOrchestra database user is owner of database. If your database is dedicated to geOrchestra (no other 
apps are running in same database), you can use following procedure to reset ownership of all objects to selected user, for 
example ```www-data``` :

```
wget https://raw.githubusercontent.com/georchestra/georchestra/15.12/postgresql/fix-owner.sql -O /tmp/fix-owner.sql
psql -d georchestra -f /tmp/fix-owner.sql
psql -d georchestra -c "SELECT change_owner('mapfishapp', 'www-data');";
psql -d georchestra -c "SELECT change_owner('downloadform', 'www-data');";
psql -d georchestra -c "SELECT change_owner('ldapadmin', 'www-data');";
psql -d georchestra -c "SELECT change_owner('ogcstatistics', 'www-data');";
psql -d georchestra -c "SELECT change_owner('public', 'www-data');";
```

## GeoNetwork schema

If **geonetwork** is to be deployed, you need to create a dedicated user and schema:
```
createuser -SDRI geonetwork
psql -d georchestra -c "ALTER USER geonetwork WITH PASSWORD 'www-data';"
psql -d georchestra -c 'CREATE SCHEMA AUTHORIZATION geonetwork;'
psql -d georchestra -c 'GRANT USAGE ON SCHEMA downloadform TO "geonetwork";'
psql -d georchestra -c "SELECT change_owner('geonetwork', 'geonetwork');";
psql -d georchestra -c 'GRANT SELECT ON downloadform.geonetwork_log TO "geonetwork";'
```

## Cleanup maintenance function

Finally, you can drop maintenance function :
```
psql -d georchestra -c "DROP FUNCTION change_owner(text, text);";
```
