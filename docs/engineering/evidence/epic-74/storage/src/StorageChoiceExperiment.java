package app.mnema.learning.storage_spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/** Second bounded research experiment: real UUID/JSONB encoding, never application migrations. */
public final class StorageChoiceExperiment {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX = 32, MIN = 16, BYTE_LIMIT = 16_384, GC_BATCH = 8;
    static final UUID SCOPE = UUID.fromString("74000000-0000-4000-8000-000000000001");
    final Connection db;
    long queries, transferred, writes;
    int epoch = 10;
    record Entry(UUID key, UUID value) { }
    record Edge(UUID key, UUID target, long count) { }
    record Page(UUID id, String kind, int level, long count, List<Edge> edges) { }
    record Stats(long rows, long heap, long toast, long index, long wal) { }
    record DeltaState(UUID checkpoint, int depth, int bytes, List<Entry> materialized) { }
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    @FunctionalInterface interface Action { void run() throws Exception; }

    StorageChoiceExperiment(Connection db) { this.db = db; }
    static Connection connect() throws SQLException {
        int port = Integer.parseInt(System.getenv().getOrDefault("R74_PORT", "15474"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("invalid loopback port");
        Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/mnema_r74_storage", "mnema", "synthetic-only");
        try (var s = c.createStatement()) { s.execute("SET search_path TO r74_choice; SET statement_timeout='30s'; SET lock_timeout='10s'"); }
        return c;
    }
    public static void main(String[] args) throws Exception {
        try (Connection c = connect()) {
            var x = new StorageChoiceExperiment(c);
            x.schema();
            System.out.println("ENV," + x.text("SELECT version()") + ",java=" + System.getProperty("java.version")+",max_heap="+Runtime.getRuntime().maxMemory());
            System.out.println("CONFIG," + x.text("SELECT string_agg(name||'='||setting,';' ORDER BY name) FROM pg_settings WHERE name IN ('autovacuum','fsync','full_page_writes','synchronous_commit','shared_buffers','max_connections')"));
            System.out.println("METRIC,label,operations,logical_row_mutations,payload_queries,logical_payload_bytes,heap_delta,toast_delta,index_delta,wal_bytes,p50_ms,p95_ms,p99_ms");
            x.boundaries();
            x.treeProperties();
            x.deltaBoundaries();
            for (int n : new int[]{1000,10000,50000,100000}) x.members(n);
            x.documents();
            x.publication();
            x.expiryIndexProof();
            x.gc();
            System.out.println("PASS,CHOICE_EXPERIMENT_ALL_ASSERTIONS");
            System.out.println("TOTAL," + x.stats());
            for(String table:List.of("object","edge","root","garbage","revision","deck","receipt","projection","checkpoint","patch","current_node","generation_state","generation","delta_deck","overlay"))
                System.out.println("ROWS,"+table+","+x.number("SELECT count(*) FROM "+table));
        }
    }
    void schema() throws SQLException {
        sql("CREATE SCHEMA r74_choice");
        sql("""
            CREATE TABLE object(scope uuid NOT NULL, id uuid NOT NULL, kind text NOT NULL CHECK(kind IN ('block','members','content')),
              level int NOT NULL CHECK(level BETWEEN -1 AND 8), total bigint NOT NULL CHECK(total>=0), payload jsonb,
              born int NOT NULL, PRIMARY KEY(scope,id), CHECK(payload IS NULL OR octet_length(payload::text)<=16384));
            CREATE TABLE edge(scope uuid NOT NULL, parent uuid NOT NULL, ordinal int NOT NULL CHECK(ordinal BETWEEN 0 AND 31),
              node_key uuid, child uuid NOT NULL, amount bigint NOT NULL CHECK(amount>=0), PRIMARY KEY(scope,parent,ordinal),
              FOREIGN KEY(scope,parent) REFERENCES object(scope,id) ON DELETE CASCADE,
              FOREIGN KEY(scope,child) REFERENCES object(scope,id));
            CREATE INDEX edge_incoming ON edge(scope,child);
            CREATE TABLE root(scope uuid NOT NULL, name text NOT NULL, object_id uuid NOT NULL, expires int,
              PRIMARY KEY(scope,name), FOREIGN KEY(scope,object_id) REFERENCES object(scope,id));
            CREATE INDEX root_incoming ON root(scope,object_id);
            CREATE INDEX root_expiry ON root(scope,expires) WHERE expires IS NOT NULL;
            CREATE TABLE garbage(scope uuid NOT NULL, object_id uuid NOT NULL, eligible int NOT NULL,
              PRIMARY KEY(scope,object_id));
            CREATE INDEX garbage_ready ON garbage(scope,eligible,object_id);
            CREATE TABLE revision(id uuid PRIMARY KEY, scope uuid NOT NULL, root uuid NOT NULL, parent uuid,
              FOREIGN KEY(scope,root) REFERENCES object(scope,id));
            CREATE INDEX revision_root ON revision(scope,root);
            CREATE TABLE deck(id uuid PRIMARY KEY, head uuid NOT NULL REFERENCES revision, version bigint NOT NULL,
              source_deck uuid, source_base uuid REFERENCES revision);
            CREATE TABLE receipt(deck uuid NOT NULL REFERENCES deck, command uuid NOT NULL, payload text NOT NULL,
              result uuid NOT NULL REFERENCES revision, PRIMARY KEY(deck,command));
            CREATE TABLE projection(deck uuid NOT NULL REFERENCES deck, node_key uuid NOT NULL, value uuid NOT NULL,
              PRIMARY KEY(deck,node_key));
            CREATE TABLE checkpoint(id uuid PRIMARY KEY, members jsonb, document jsonb);
            CREATE TABLE patch(checkpoint uuid NOT NULL REFERENCES checkpoint, depth int NOT NULL CHECK(depth BETWEEN 1 AND 32),
              operation text NOT NULL, position int NOT NULL, target int, item jsonb, PRIMARY KEY(checkpoint,depth));
            CREATE TABLE current_node(document uuid NOT NULL, ordinal int NOT NULL, payload jsonb NOT NULL, PRIMARY KEY(document,ordinal));
            CREATE TABLE generation_state(id uuid PRIMARY KEY, ready boolean NOT NULL);
            CREATE TABLE generation(id uuid NOT NULL REFERENCES generation_state, ordinal int NOT NULL, node_key uuid NOT NULL, value uuid NOT NULL,
              PRIMARY KEY(id,ordinal), UNIQUE(id,node_key));
            CREATE TABLE delta_deck(id uuid PRIMARY KEY, generation uuid NOT NULL REFERENCES generation_state, source uuid);
            CREATE TABLE overlay(deck uuid NOT NULL REFERENCES delta_deck, ordinal int NOT NULL, value uuid NOT NULL, PRIMARY KEY(deck,ordinal));
            """);
    }
    <T> T tx(Work<T> work) throws Exception {
        db.setAutoCommit(false);
        try { T value = work.run(); db.commit(); return value; }
        catch (Exception | AssertionError e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }
    void sql(String q, Object... args) throws SQLException { try (var s=db.prepareStatement(q)) { bind(s,args); s.execute(); } }
    static void bind(java.sql.PreparedStatement s,Object...args) throws SQLException { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    long number(String q,Object...args) throws SQLException { try(var s=db.prepareStatement(q)){bind(s,args);try(var r=s.executeQuery()){r.next();return r.getLong(1);}} }
    String text(String q,Object...args) throws SQLException { try(var s=db.prepareStatement(q)){bind(s,args);try(var r=s.executeQuery()){r.next();return r.getString(1);}} }
    static void check(boolean yes,String message) { if(!yes) throw new AssertionError(message); }
    static UUID uuid() { return UUID.randomUUID(); }
    static String json(Object value) throws Exception { return JSON.writeValueAsString(value); }
    void enqueue(UUID scope,UUID id) throws SQLException { sql("INSERT INTO garbage VALUES (?,?,?) ON CONFLICT(scope,object_id) DO UPDATE SET eligible=excluded.eligible",scope,id,epoch+2);writes++; }

    UUID block(UUID scope,JsonNode payload) throws Exception {
        if(db.getAutoCommit())return tx(()->block(scope,payload));
        String encoded=json(payload);
        // SQL's JSONB text includes spaces; check the actual persisted semantic representation.
        int bytes=(int)number("SELECT octet_length(?::jsonb::text)",encoded);
        if(bytes>BYTE_LIMIT) throw new IllegalArgumentException("native block exceeds 16384 bytes; split the paragraph without changing existing node identities");
        UUID id=uuid();
        sql("INSERT INTO object VALUES (?,?, 'block',-1,1,?::jsonb,?)",scope,id,encoded,epoch);
        writes++; enqueue(scope,id); return id;
    }
    JsonNode nativeNode(UUID node,int seed,int length) {
        var n=JSON.createObjectNode(); n.put("id",node.toString());n.put("type","paragraph");n.put("version",1);
        n.putObject("attrs").put("lang","ru").put("dir","auto");
        var t=n.putArray("content").addObject();t.put("id",UUID.nameUUIDFromBytes(node.toString().getBytes(StandardCharsets.UTF_8)).toString());
        t.put("type","text");t.put("version",1);t.putObject("attrs");
        Random random=new Random(seed);StringBuilder text=new StringBuilder("Материал ");
        while(text.length()<length)text.append((char)('a'+random.nextInt(26)));
        t.put("text",text.toString());return n;
    }
    UUID save(UUID scope,String kind,int level,List<Edge> edges) throws Exception {
        if(db.getAutoCommit())return tx(()->save(scope,kind,level,edges));
        if(edges.size()>MAX)throw new IllegalArgumentException("fanout");
        if(level>0 && edges.isEmpty())throw new IllegalArgumentException("empty internal");
        Set<UUID> keys=new HashSet<>();long total=0;
        for(Edge edge:edges) {
            if(level==0 && (edge.key==null || !keys.add(edge.key)))throw new IllegalArgumentException("duplicate/missing key");
            if(level>0 && edge.key!=null)throw new IllegalArgumentException("internal key");
            total+=level==0?1:edge.count;
        }
        if(!edges.isEmpty()) {
            queries++;
            try(var s=db.prepareStatement("SELECT id,kind,level,total FROM object WHERE scope=? AND id=ANY(?)")) {
                s.setObject(1,scope);s.setArray(2,db.createArrayOf("uuid",edges.stream().map(Edge::target).toArray()));
                Map<UUID,Edge> targets=new HashMap<>();for(Edge e:edges)targets.put(e.target,e);
                Set<UUID> found=new HashSet<>();
                try(var r=s.executeQuery()) { while(r.next()) {
                    UUID id=r.getObject(1,UUID.class);found.add(id);
                    for(Edge e:edges)if(e.target.equals(id)) {
                        boolean valid=level==0 ? r.getString(2).equals("block") && e.count==1
                            : r.getString(2).equals(kind)&&r.getInt(3)==level-1&&r.getLong(4)==e.count;
                        if(!valid)throw new IllegalArgumentException("wrong kind/level/subtree count");
                    }
                }}
                if(found.size()!=targets.size())throw new IllegalArgumentException("dangling or cross-scope edge");
            }
        }
        // Exact transport representation is bounded in addition to the 32-edge relational encoding.
        if(json(edges).getBytes(StandardCharsets.UTF_8).length>BYTE_LIMIT)throw new IllegalArgumentException("page bytes");
        UUID id=uuid();sql("INSERT INTO object VALUES (?,?,?,?,?,NULL,?)",scope,id,kind,level,total,epoch);writes++;
        try(var s=db.prepareStatement("INSERT INTO edge VALUES (?,?,?,?,?,?)")) {
            for(int i=0;i<edges.size();i++){Edge e=edges.get(i);bind(s,scope,id,i,e.key,e.target,e.count);s.addBatch();writes++;}
            s.executeBatch();
        }
        enqueue(scope,id);return id;
    }
    Page load(UUID scope,UUID id) throws Exception {
        queries++;
        try(var s=db.prepareStatement("SELECT o.kind,o.level,o.total,e.node_key,e.child,e.amount FROM object o LEFT JOIN edge e ON e.scope=o.scope AND e.parent=o.id WHERE o.scope=? AND o.id=? ORDER BY e.ordinal")) {
            bind(s,scope,id);try(var r=s.executeQuery()) {
                check(r.next(),"page exists");String kind=r.getString(1);int level=r.getInt(2);long total=r.getLong(3);
                check(!kind.equals("block"),"expected page");List<Edge> edges=new ArrayList<>();
                do { UUID child=r.getObject(5,UUID.class);if(child!=null)edges.add(new Edge(r.getObject(4,UUID.class),child,r.getLong(6))); }while(r.next());
                transferred+=json(edges).getBytes(StandardCharsets.UTF_8).length;
                return new Page(id,kind,level,total,edges);
            }
        }
    }
    UUID build(UUID scope,String kind,List<Entry> values) throws Exception {
        if(values.isEmpty())return save(scope,kind,0,List.of());
        List<UUID> level=new ArrayList<>();
        for(List<Entry> group:groups(values)) {
            List<Edge> e=new ArrayList<>();for(Entry v:group)e.add(new Edge(v.key,v.value,1));
            level.add(save(scope,kind,0,e));
        }
        int height=1;
        while(level.size()>1){List<UUID> next=new ArrayList<>();for(List<UUID> group:groups(level)){List<Edge> e=new ArrayList<>();for(UUID id:group)e.add(new Edge(null,id,load(scope,id).count));next.add(save(scope,kind,height,e));}level=next;height++;}
        return level.getFirst();
    }
    static <T> List<List<T>> groups(List<T> list) {
        int groups=(list.size()+MAX-1)/MAX;List<List<T>> out=new ArrayList<>();int start=0;
        for(int i=0;i<groups;i++){int size=(list.size()-start+groups-i-1)/(groups-i);out.add(list.subList(start,start+size));start+=size;}return out;
    }
    UUID mutation(UUID scope,UUID root,int position,Entry item,String operation) throws Exception {
        List<UUID> replacement=change(scope,root,position,item,operation);
        if(replacement.isEmpty())return save(scope,load(scope,root).kind,0,List.of());
        UUID result;
        if(replacement.size()==1)result=replacement.getFirst();
        else {Page first=load(scope,replacement.getFirst());List<Edge> e=new ArrayList<>();for(UUID id:replacement)e.add(new Edge(null,id,load(scope,id).count));result=save(scope,first.kind,first.level+1,e);}
        Page p=load(scope,result);while(p.level>0&&p.edges.size()==1){result=p.edges.getFirst().target;p=load(scope,result);}return result;
    }
    List<UUID> change(UUID scope,UUID id,int position,Entry item,String operation) throws Exception {
        Page p=load(scope,id);List<Edge> e=new ArrayList<>(p.edges);
        if(p.level==0) {
            switch(operation){case "insert"->e.add(position,new Edge(item.key,item.value,1));case "delete"->e.remove(position);case "edit"->e.set(position,new Edge(item.key,item.value,1));default->throw new IllegalArgumentException(operation);}
        } else {
            int i=0;while(i<e.size()-1&&position>=e.get(i).count){position-=Math.toIntExact(e.get(i).count);i++;}
            List<UUID> changed=change(scope,e.get(i).target,position,item,operation);e.remove(i);
            for(int j=0;j<changed.size();j++){Page c=load(scope,changed.get(j));e.add(i+j,new Edge(null,c.id,c.count));}
            if(operation.equals("delete") && e.size()>1) {
                int suspect=Math.min(i,e.size()-1);Page child=load(scope,e.get(suspect).target);
                if(child.edges.size()<MIN){
                    int left=suspect==0?0:suspect-1;Page a=load(scope,e.get(left).target),b=load(scope,e.get(left+1).target);
                    List<Edge> combined=new ArrayList<>(a.edges);combined.addAll(b.edges);e.remove(left+1);e.remove(left);
                    int slot=left;
                    for(List<Edge> group:groups(combined)){UUID merged=save(scope,p.kind,p.level-1,group);e.add(slot++,new Edge(null,merged,group.stream().mapToLong(v->p.level==1?1:v.count).sum()));}
                }
            }
        }
        if(e.isEmpty()&&p.level>0)return List.of();
        List<UUID> out=new ArrayList<>();
        if(e.isEmpty())out.add(save(scope,p.kind,0,e));
        else for(List<Edge> group:groups(e))out.add(save(scope,p.kind,p.level,group));
        return out;
    }
    List<Entry> page(UUID scope,UUID root,int offset,int limit) throws Exception {List<Entry> out=new ArrayList<>();slice(scope,root,offset,limit,out);return out;}
    void slice(UUID scope,UUID root,int offset,int limit,List<Entry> out)throws Exception{
        Page p=load(scope,root);if(p.level==0){for(int i=offset;i<p.edges.size()&&out.size()<limit;i++){Edge e=p.edges.get(i);out.add(new Entry(e.key,e.target));}}
        else for(Edge e:p.edges){if(out.size()>=limit)break;if(offset>=e.count)offset-=Math.toIntExact(e.count);else{slice(scope,e.target,offset,limit,out);offset=0;}}
    }
    long verify(UUID scope,UUID id,boolean root)throws Exception{
        Page p=load(scope,id);check(p.edges.size()<=MAX,"max occupancy");
        if(!root)check(p.edges.size()>=MIN,"minimum occupancy");
        if(root&&p.level>0)check(p.edges.size()>=2,"collapsed root");
        if(root&&p.level>0)check(p.count>=2L*(long)Math.pow(MIN,p.level),"height bound follows current member count, not historic shape");
        long total=0;for(Edge e:p.edges){if(p.level==0)total++;else{Page child=load(scope,e.target);check(child.level==p.level-1,"balanced depth");long actual=verify(scope,e.target,false);check(actual==e.count,"subtree count");total+=actual;}}
        check(total==p.count,"page total");return total;
    }
    void treeProperties() throws Exception {
        UUID scope=uuid(),value=block(scope,nativeNode(uuid(),1,64));List<Entry> model=new ArrayList<>();UUID root=build(scope,"members",model);
        List<UUID> historicalRoots=new ArrayList<>();List<List<Entry>> historicalModels=new ArrayList<>();Random rng=new Random(74);
        for(int boundary:new int[]{1,15,16,17,31,32,33,511,512,513,1024,1057}) {
            while(model.size()<boundary){Entry e=new Entry(uuid(),value);int pos=rng.nextInt(model.size()+1);UUID previous=root;root=tx(()->mutation(scope,previous,pos,e,"insert"));model.add(pos,e);}
            verify(scope,root,true);check(page(scope,root,0,model.size()).equals(model),"growth key/value parity");historicalRoots.add(root);historicalModels.add(List.copyOf(model));
        }
        while(!model.isEmpty()) {int pos=rng.nextInt(model.size());UUID previous=root;root=tx(()->mutation(scope,previous,pos,null,"delete"));model.remove(pos);if(model.size()%31==0||model.size()<34){verify(scope,root,true);check(page(scope,root,0,model.size()+1).equals(model),"shrink parity");}}
        check(load(scope,root).level==0&&load(scope,root).count==0,"empty root");
        for(int step=0;step<350;step++) {
            UUID previous=root;int op=model.isEmpty()?0:rng.nextInt(4);
            if(op==0){int pos=rng.nextInt(model.size()+1);Entry e=new Entry(uuid(),value);root=tx(()->mutation(scope,previous,pos,e,"insert"));model.add(pos,e);}
            else if(op==1){int pos=rng.nextInt(model.size());root=tx(()->mutation(scope,previous,pos,null,"delete"));model.remove(pos);}
            else if(op==2){int pos=rng.nextInt(model.size());Entry e=new Entry(model.get(pos).key,block(scope,nativeNode(uuid(),step,64)));root=tx(()->mutation(scope,previous,pos,e,"edit"));model.set(pos,e);}
            else{int from=rng.nextInt(model.size());Entry e=model.remove(from);int to=rng.nextInt(model.size()+1);root=tx(()->mutation(scope,mutation(scope,previous,from,null,"delete"),to,e,"insert"));model.add(to,e);}
            verify(scope,root,true);check(page(scope,root,0,model.size()+1).equals(model),"churn key/value parity");
        }
        for(int i=0;i<historicalRoots.size();i++)check(page(scope,historicalRoots.get(i),0,historicalModels.get(i).size()).equals(historicalModels.get(i)),"retained historic key/value parity");
        System.out.println("PASS,SR2 grow across 12 boundaries to 1057; random shrink to zero; regrow/churn350; min16/max32/merge/redistribute/collapse/counts/history values");
    }
    void boundaries() throws Exception {
        UUID scope=uuid(),node=uuid();JsonNode content=nativeNode(node,1,80);UUID b=block(scope,content);
        UUID root=build(scope,"content",List.of(new Entry(node,b)));check(document(scope,root).equals(List.of(content)),"short item exact round-trip");
        int low=80,high=BYTE_LIMIT;while(low+1<high){int mid=(low+high)/2;if(number("SELECT octet_length(?::jsonb::text)",json(nativeNode(node,2,mid)))<=BYTE_LIMIT)low=mid;else high=mid;}
        int acceptedLength=low;UUID near=block(scope,nativeNode(node,2,acceptedLength));
        check(number("SELECT octet_length(payload::text) FROM object WHERE scope=? AND id=?",scope,near)==BYTE_LIMIT,"exact 16KiB boundary");
        reject(()->block(scope,nativeNode(node,2,acceptedLength+1)),"over-limit native node");
        check(JSON.readTree(text("SELECT payload::text FROM object WHERE scope=? AND id=?",scope,near)).path("id").asText().equals(node.toString()),"stable node identity at boundary");
        long before=number("SELECT count(*) FROM object");
        reject(()->save(scope,"content",0,List.of(new Edge(node,uuid(),1))),"dangling");
        reject(()->save(uuid(),"content",0,List.of(new Edge(node,b,1))),"cross-scope");
        reject(()->save(scope,"members",1,List.of(new Edge(null,root,1))),"wrong page kind");
        reject(()->save(scope,"content",1,List.of(new Edge(null,root,2))),"wrong subtree count");
        check(number("SELECT count(*) FROM object")==before,"invalid pages never staged");
        try{sql("INSERT INTO edge VALUES (?,?,1,?,?,1)",scope,root,node,uuid());throw new AssertionError("dangling FK accepted");}
        catch(SQLException expected){check(expected.getSQLState().equals("23503"),"SQL FK rejects missing child");}
        System.out.println("PASS,SR4 UUID/native short exact round-trip;16384-byte accept/16385 reject;stableIDs;dangling/wrongkind/wrongcount/crossscope rejected");
    }
    void reject(Work<?> work,String name)throws Exception{try{work.run();throw new AssertionError(name+" accepted");}catch(IllegalArgumentException expected){check(expected.getMessage()!=null,name);}}
    List<JsonNode> document(UUID scope,UUID root)throws Exception{
        List<Entry> entries=page(scope,root,0,10000);queries++;
        Map<UUID,JsonNode> blocks=new HashMap<>();
        try(var s=db.prepareStatement("SELECT id,payload::text FROM object WHERE scope=? AND id=ANY(?)")){
            s.setObject(1,scope);s.setArray(2,db.createArrayOf("uuid",entries.stream().map(Entry::value).toArray()));
            try(var r=s.executeQuery()){while(r.next()){String payload=r.getString(2);transferred+=payload.getBytes(StandardCharsets.UTF_8).length;blocks.put(r.getObject(1,UUID.class),JSON.readTree(payload));}}
        }
        List<JsonNode> out=new ArrayList<>();for(Entry e:entries){JsonNode n=blocks.get(e.value);check(n!=null&&n.path("id").asText().equals(e.key.toString()),"document identity/order");out.add(n);}return out;
    }

    UUID checkpoint(List<Entry> entries,List<JsonNode> doc)throws Exception{
        UUID id=uuid();sql("INSERT INTO checkpoint VALUES (?,?::jsonb,?::jsonb)",id,entries==null?null:json(entries),doc==null?null:json(doc));writes++;return id;
    }
    DeltaState deltaEdit(DeltaState state,String operation,int position,int target,Entry item)throws Exception{
        List<Entry> materialized=new ArrayList<>(state.materialized);
        switch(operation){case "insert"->materialized.add(position,item);case "edit"->materialized.set(position,item);case "delete"->materialized.remove(position);case "move"->{Entry e=materialized.remove(position);materialized.add(target,e);}default->throw new IllegalArgumentException(operation);}
        int bytes=operation.length()+32+(item==null?0:json(item).getBytes(StandardCharsets.UTF_8).length);
        if(state.depth==32||state.bytes+bytes>4096)return new DeltaState(checkpoint(materialized,null),0,0,materialized);
        sql("INSERT INTO patch VALUES (?,?,?,?,?,?::jsonb)",state.checkpoint,state.depth+1,operation,position,target,item==null?null:json(item));writes++;
        return new DeltaState(state.checkpoint,state.depth+1,state.bytes+bytes,materialized);
    }
    List<Entry> deltaRead(DeltaState state)throws Exception{
        queries++;String encoded=text("SELECT members::text FROM checkpoint WHERE id=?",state.checkpoint);transferred+=encoded.getBytes(StandardCharsets.UTF_8).length;
        List<Entry> values=new ArrayList<>();for(JsonNode e:JSON.readTree(encoded))values.add(new Entry(UUID.fromString(e.path("key").asText()),UUID.fromString(e.path("value").asText())));
        queries++;try(var s=db.prepareStatement("SELECT operation,position,target,item::text FROM patch WHERE checkpoint=? AND depth<=? ORDER BY depth")){
            bind(s,state.checkpoint,state.depth);try(var r=s.executeQuery()){while(r.next()){
                String operation=r.getString(1),payload=r.getString(4);int pos=r.getInt(2),target=r.getInt(3);Entry item=null;
                if(payload!=null){transferred+=payload.getBytes(StandardCharsets.UTF_8).length;JsonNode e=JSON.readTree(payload);item=new Entry(UUID.fromString(e.path("key").asText()),UUID.fromString(e.path("value").asText()));}
                switch(operation){case "insert"->values.add(pos,item);case "edit"->values.set(pos,item);case "delete"->values.remove(pos);case "move"->{Entry e=values.remove(pos);values.add(target,e);}default->throw new AssertionError(operation);}
            }}}
        return values;
    }
    void deltaBoundaries()throws Exception{
        List<Entry> original=new ArrayList<>();for(int i=0;i<128;i++)original.add(new Entry(uuid(),uuid()));
        DeltaState state=new DeltaState(checkpoint(original,null),0,0,List.copyOf(original));List<DeltaState> history=new ArrayList<>(List.of(state));
        for(int i=0;i<33;i++){state=deltaEdit(state,"delete",0,0,null);history.add(state);}
        check(history.get(32).depth==32&&history.get(33).depth==0,"count checkpoint exact rollover32/33");
        Random random=new Random(743);int maxBytes=0;
        for(int i=0;i<180;i++){
            String op=List.of("edit","insert","move","delete").get(i%4);int pos=random.nextInt(state.materialized.size());
            Entry item=new Entry(op.equals("insert")?uuid():state.materialized.get(pos).key,uuid());
            state=deltaEdit(state,op,pos,random.nextInt(state.materialized.size()),item);history.add(state);maxBytes=Math.max(maxBytes,state.bytes);
            check(deltaRead(state).equals(state.materialized),"delta mixed independent retained-state parity");
        }
        for(DeltaState old:history)check(deltaRead(old).equals(old.materialized),"delta historical checkpoint/patch parity");
        check(maxBytes<=4096,"membership byte replay bound");
        System.out.println("PASS,SR3 delta count32/33 rollover;180 insert/edit/delete/move;all214 retained snapshots exact;4096-byte membership patch budget");
    }
    void members(int n)throws Exception{
        UUID scope=uuid(),node=uuid(),block=block(scope,nativeNode(node,n,128));List<Entry> model=new ArrayList<>();for(int i=0;i<n;i++)model.add(new Entry(uuid(),block));
        UUID[] tree={null};measure("uuid_tree_"+n+"_build",1,()->tree[0]=tx(()->build(scope,"members",model)));
        UUID initial=tree[0];DeltaState[] delta={new DeltaState(checkpoint(model,null),0,0,List.copyOf(model))};
        measure("uuid_tree_"+n+"_metadata",10,()->{UUID revision=uuid();sql("INSERT INTO revision VALUES (?,?,?,NULL)",revision,scope,tree[0]);writes++;});
        for(String op:List.of("edit","delete","move")){
            int pos=model.size()/2;Entry entry=new Entry(model.get(pos).key,block);if(op.equals("edit"))entry=new Entry(entry.key,block(scope,nativeNode(node,n+1,128)));
            Entry selected=entry;String operation=op;
            measure("uuid_tree_"+n+"_"+op,1,()->tree[0]=tx(()->operation.equals("move")?mutation(scope,mutation(scope,tree[0],pos,null,"delete"),0,selected,"insert"):mutation(scope,tree[0],pos,selected,operation)));
            measure("uuid_delta_"+n+"_"+op,1,()->delta[0]=tx(()->deltaEdit(delta[0],operation,pos,0,selected)));
            if(op.equals("edit"))model.set(pos,selected);else if(op.equals("delete"))model.remove(pos);else{Entry e=model.remove(pos);model.addFirst(e);}
            check(page(scope,tree[0],0,n).equals(model)&&deltaRead(delta[0]).equals(model),"equal member "+op);
        }
        List<Entry> bulk=new ArrayList<>();for(int i=0;i<100;i++)bulk.add(new Entry(model.get(i).key,block));
        measure("uuid_tree_"+n+"_bulk100",1,()->tree[0]=tx(()->{UUID r=tree[0];for(int i=0;i<100;i++)r=mutation(scope,r,i,bulk.get(i),"edit");return r;}));
        measure("uuid_delta_"+n+"_bulk100",1,()->delta[0]=tx(()->{DeltaState d=delta[0];for(int i=0;i<100;i++)d=deltaEdit(d,"edit",i,0,bulk.get(i));return d;}));
        for(int i=0;i<100;i++)model.set(i,bulk.get(i));check(deltaRead(delta[0]).equals(model)&&page(scope,tree[0],0,n).equals(model),"bulk/rollover exact parity");
        measure("uuid_tree_"+n+"_page100",10,()->check(page(scope,tree[0],0,100).equals(model.subList(0,100)),"tree100"));
        measure("uuid_delta_"+n+"_array_page100",10,()->check(deltaRead(delta[0]).subList(0,100).equals(model.subList(0,100)),"delta100"));
        verify(scope,tree[0],true);
        if(n==100000)preparedFork(scope,initial,model,block);
        System.out.println("PASS,SR3 equal membership edits/delete/move/bulk100/checkpoint rollover n="+n);
    }
    void preparedFork(UUID scope,UUID tree,List<Entry> current,UUID block)throws Exception{
        // Both fork candidates start from the same saved content generation.
        List<Entry> source=page(scope,tree,0,100000);UUID generation=uuid();
        measure("delta_prepared_generation100000",1,()->{
            sql("INSERT INTO generation_state VALUES (?,false)",generation);writes++;
            check(number("WITH inserted AS (INSERT INTO delta_deck SELECT ?,id,NULL FROM generation_state WHERE id=? AND ready RETURNING id) SELECT count(*) FROM inserted",uuid(),generation)==0,"incomplete generation cannot fork");
            for(int start=0;start<source.size();start+=1000){int first=start;tx(()->{
                try(var s=db.prepareStatement("INSERT INTO generation VALUES (?,?,?,?)")){for(int i=first;i<Math.min(first+1000,source.size());i++){Entry e=source.get(i);bind(s,generation,i,e.key,e.value);s.addBatch();}s.executeBatch();}return null;});}
            sql("UPDATE generation_state SET ready=true WHERE id=?",generation);writes+=source.size()+1;
        });
        UUID[] previous={null};UUID[] treeHead={null};
        measure("delta_prepared_fork_chain25_first100",25,()->{UUID id=uuid();sql("INSERT INTO delta_deck SELECT ?,id,? FROM generation_state WHERE id=? AND ready",id,previous[0],generation);writes++;previous[0]=id;check(generationPage(id,0,100).equals(source.subList(0,100)),"prepared fork page");});
        measure("tree_fork_chain25_first100",25,()->tx(()->{UUID rev=uuid(),deck=uuid();sql("INSERT INTO revision VALUES (?,?,?,NULL)",rev,scope,tree);sql("INSERT INTO deck VALUES (?,?,0,?,?)",deck,rev,treeHead[0],treeHead[0]==null?null:UUID.fromString(text("SELECT head FROM deck WHERE id=?",treeHead[0])));writes+=2;treeHead[0]=deck;check(page(scope,tree,0,100).equals(source.subList(0,100)),"tree fork page");return null;}));
        UUID fork=previous[0];UUID privateBlock=block(scope,nativeNode(uuid(),51,128));
        measure("delta_prepared_fork_private_edit",1,()->{sql("INSERT INTO overlay VALUES (?,?,?)",fork,50,privateBlock);writes++;});
        List<Entry> expected=new ArrayList<>(source.subList(0,100));expected.set(50,new Entry(expected.get(50).key,privateBlock));check(generationPage(fork,0,100).equals(expected),"sparse fork edit");
        check(number("SELECT count(*) FROM overlay WHERE deck<>?",fork)==0,"no source/fork sibling overlay writes");
        System.out.println("PASS,SR3 prepared100k delta generation fork-of-fork25 independent sparse edit, generation preparation excluded from foreground");
    }
    List<Entry> generationPage(UUID deck,int offset,int limit)throws Exception{
        queries++;List<Entry> result=new ArrayList<>();try(var s=db.prepareStatement("SELECT g.node_key,coalesce(o.value,g.value) FROM delta_deck d JOIN generation g ON g.id=d.generation LEFT JOIN overlay o ON o.deck=d.id AND o.ordinal=g.ordinal WHERE d.id=? AND g.ordinal>=? AND g.ordinal<? ORDER BY g.ordinal")){
            bind(s,deck,offset,offset+limit);try(var r=s.executeQuery()){while(r.next())result.add(new Entry(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));}}
        transferred+=json(result).getBytes(StandardCharsets.UTF_8).length;return result;
    }

    void documents() throws Exception {
        UUID scope=uuid(),document=uuid();List<JsonNode> model=new ArrayList<>();List<Entry> entries=new ArrayList<>();
        for(int i=0;i<128;i++){UUID key=uuid();JsonNode node=nativeNode(key,i,2048);model.add(node);entries.add(new Entry(key,block(scope,node)));sql("INSERT INTO current_node VALUES (?,?,?::jsonb)",document,i,json(node));}
        UUID[] tree={build(scope,"content",entries)};UUID[] checkpoint={checkpoint(null,model)};int[] depth={0},bytes={0},iteration={0};
        List<UUID> treeHistory=new ArrayList<>(List.of(tree[0]));List<UUID> deltaCp=new ArrayList<>(List.of(checkpoint[0]));List<Integer> deltaDepth=new ArrayList<>(List.of(0));List<List<JsonNode>> history=new ArrayList<>(List.of(List.copyOf(model)));
        measure("native_tree_document1000edits",1000,()->{int edit=++iteration[0];JsonNode changed=nativeNode(entries.getFirst().key,10000+edit,2048);tree[0]=tx(()->mutation(scope,tree[0],0,new Entry(entries.getFirst().key,block(scope,changed)),"edit"));model.set(0,changed);treeHistory.add(tree[0]);history.add(List.copyOf(model));});
        iteration[0]=0;
        measure("native_delta_nodehead1000edits",1000,()->tx(()->{
            int edit=++iteration[0];JsonNode changed=history.get(edit).getFirst();String encoded=json(changed);int patchBytes=encoded.getBytes(StandardCharsets.UTF_8).length;
            sql("UPDATE current_node SET payload=?::jsonb WHERE document=? AND ordinal=0",encoded,document);writes++;
            if(depth[0]==32||bytes[0]+patchBytes>65536){List<JsonNode> current=new ArrayList<>();try(var s=db.prepareStatement("SELECT payload::text FROM current_node WHERE document=? ORDER BY ordinal")){bind(s,document);try(var r=s.executeQuery()){while(r.next())current.add(JSON.readTree(r.getString(1)));}}checkpoint[0]=checkpoint(null,current);depth[0]=0;bytes[0]=0;}
            else{sql("INSERT INTO patch VALUES (?,?, 'node',0,NULL,?::jsonb)",checkpoint[0],++depth[0],encoded);writes++;bytes[0]+=patchBytes;}
            deltaCp.add(checkpoint[0]);deltaDepth.add(depth[0]);return null;}));
        for(int revision:new int[]{0,1,31,32,33,999,1000}){
            measure("native_tree_equal_document_rev"+revision,10,()->check(document(scope,treeHistory.get(revision)).equals(history.get(revision)),"tree exact historic native values"));
            measure("native_delta_equal_document_rev"+revision,10,()->check(deltaDocument(deltaCp.get(revision),deltaDepth.get(revision)).equals(history.get(revision)),"delta exact historic native values"));
        }
        check(deltaDepth.stream().mapToInt(Integer::intValue).max().orElseThrow()<32,"byte-triggered checkpoint occurred before 32");
        System.out.println("PASS,SR3 identical native payloads/order/nodeIDs at revisions0/1/31/32/33/999/1000;node-head delta checkpoint+patch transfer and client replay;64KiB byte rollover");
    }
    List<JsonNode> deltaDocument(UUID cp,int depth)throws Exception{
        queries++;String checkpoint=text("SELECT document::text FROM checkpoint WHERE id=?",cp);transferred+=checkpoint.getBytes(StandardCharsets.UTF_8).length;
        List<JsonNode> result=new ArrayList<>();JSON.readTree(checkpoint).forEach(result::add);
        queries++;try(var s=db.prepareStatement("SELECT position,item::text FROM patch WHERE checkpoint=? AND depth<=? ORDER BY depth")){bind(s,cp,depth);try(var r=s.executeQuery()){while(r.next()){String payload=r.getString(2);transferred+=payload.getBytes(StandardCharsets.UTF_8).length;result.set(r.getInt(1),JSON.readTree(payload));}}}return result;
    }

    UUID publish(UUID deck,UUID command,String payload,long expected,UUID scope,UUID root,Entry value)throws Exception{return tx(()->publishInside(deck,command,payload,expected,scope,root,value));}
    UUID publishInside(UUID deck,UUID command,String payload,long expected,UUID scope,UUID root,Entry value)throws Exception{
        long version=number("SELECT version FROM deck WHERE id=? FOR UPDATE",deck);
        try(var s=db.prepareStatement("SELECT payload,result FROM receipt WHERE deck=? AND command=?")){bind(s,deck,command);try(var r=s.executeQuery()){if(r.next()){if(!payload.equals(r.getString(1)))throw new IllegalArgumentException("idempotency conflict");return r.getObject(2,UUID.class);}}}
        if(version!=expected)return null;
        UUID revision=uuid();sql("INSERT INTO revision SELECT ?,?,?,head FROM deck WHERE id=?",revision,scope,root,deck);
        sql("UPDATE deck SET head=?,version=version+1 WHERE id=? AND version=?",revision,deck,expected);
        sql("INSERT INTO projection VALUES (?,?,?) ON CONFLICT(deck,node_key) DO UPDATE SET value=excluded.value",deck,value.key,value.value);
        sql("INSERT INTO receipt VALUES (?,?,?,?)",deck,command,payload,revision);writes+=4;return revision;
    }
    void publication()throws Exception{
        UUID scope=uuid(),key=uuid(),oldBlock=block(scope,nativeNode(key,1,64)),newBlock=block(scope,nativeNode(key,2,64));
        UUID beforeRoot=build(scope,"content",List.of(new Entry(key,oldBlock))),staged=build(scope,"content",List.of(new Entry(key,newBlock)));
        UUID beforeRev=uuid(),deck=uuid(),command=uuid();sql("INSERT INTO revision VALUES (?,?,?,NULL)",beforeRev,scope,beforeRoot);sql("INSERT INTO deck VALUES (?,?,0,NULL,NULL)",deck,beforeRev);sql("INSERT INTO projection VALUES (?,?,?)",deck,key,oldBlock);
        long revisions=number("SELECT count(*) FROM revision"),receipts=number("SELECT count(*) FROM receipt");
        try(Connection worker=connect()){
            worker.setAutoCommit(false);var w=new StorageChoiceExperiment(worker);w.publishInside(deck,command,"save",0,scope,staged,new Entry(key,newBlock));
            assertUnpublished(deck,key,beforeRev,oldBlock,revisions,receipts);
        }
        assertUnpublished(deck,key,beforeRev,oldBlock,revisions,receipts);check(load(scope,staged).count==1,"staging survives crash");
        try(var clients=Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new CountDownLatch(4);var start=new CountDownLatch(1);List<Future<UUID>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(clients.submit(()->{try(Connection c=connect()){ready.countDown();check(start.await(10,TimeUnit.SECONDS),"retry start");return new StorageChoiceExperiment(c).publish(deck,command,"save",0,scope,staged,new Entry(key,newBlock));}}));
            check(ready.await(10,TimeUnit.SECONDS),"retry ready");start.countDown();Set<UUID> results=new HashSet<>();for(var future:futures)results.add(future.get(15,TimeUnit.SECONDS));check(results.size()==1&&!results.contains(null),"same-command race same result");
        }
        check(number("SELECT count(*) FROM revision")==revisions+1&&number("SELECT count(*) FROM receipt")==receipts+1,"one complete publication");
        check(text("SELECT value FROM projection WHERE deck=? AND node_key=?",deck,key).equals(newBlock.toString()),"projection committed");
        reject(()->publish(deck,command,"changed",1,scope,staged,new Entry(key,newBlock)),"changed command payload");
        check(publish(deck,uuid(),"stale",0,scope,staged,new Entry(key,newBlock))==null,"stale head rejected");
        System.out.println("PASS,SR1 worker transaction atomic revision/head/receipt/projection before and after disconnect;staging survives;four simultaneous same-command retries one complete result;payload/stale conflicts");
    }
    void assertUnpublished(UUID deck,UUID key,UUID revision,UUID value,long revisions,long receipts)throws Exception{
        check(number("SELECT count(*) FROM revision")==revisions&&number("SELECT count(*) FROM receipt")==receipts,"uncommitted rows invisible");
        check(text("SELECT head FROM deck WHERE id=?",deck).equals(revision.toString())&&number("SELECT version FROM deck WHERE id=?",deck)==0,"head/version unchanged");
        check(text("SELECT value FROM projection WHERE deck=? AND node_key=?",deck,key).equals(value.toString()),"projection unchanged");
    }

    void pin(UUID scope,String name,UUID object,Integer expires)throws SQLException{sql("INSERT INTO root VALUES (?,?,?,?)",scope,name,object,expires);}
    void release(UUID scope,String name)throws Exception{if(db.getAutoCommit()){tx(()->{release(scope,name);return null;});return;}UUID id=UUID.fromString(text("SELECT object_id FROM root WHERE scope=? AND name=?",scope,name));sql("DELETE FROM root WHERE scope=? AND name=?",scope,name);enqueue(scope,id);}
    boolean collectOne(UUID scope,UUID id)throws Exception{
        // Incoming FK references arbitrate races even when the predicate's statement snapshot is stale.
        List<UUID> children=new ArrayList<>();try(var s=db.prepareStatement("SELECT child FROM edge WHERE scope=? AND parent=? ORDER BY ordinal")){bind(s,scope,id);try(var r=s.executeQuery()){while(r.next())children.add(r.getObject(1,UUID.class));}}
        check(children.size()<=MAX,"bounded outgoing edges");
        long removed=number("WITH gone AS (DELETE FROM object o WHERE scope=? AND id=? AND NOT EXISTS(SELECT 1 FROM edge e WHERE e.scope=o.scope AND e.child=o.id) AND NOT EXISTS(SELECT 1 FROM root r WHERE r.scope=o.scope AND r.object_id=o.id) AND NOT EXISTS(SELECT 1 FROM revision r WHERE r.scope=o.scope AND r.root=o.id) RETURNING id) SELECT count(*) FROM gone",scope,id);
        sql("DELETE FROM garbage WHERE scope=? AND object_id=?",scope,id);
        if(removed==1)for(UUID child:children)enqueue(scope,child);
        return removed==1;
    }
    int gcBatch(UUID scope)throws Exception{return tx(()->{
        List<UUID> selected=new ArrayList<>();try(var s=db.prepareStatement("SELECT object_id FROM garbage WHERE scope=? AND eligible<=? ORDER BY eligible,object_id LIMIT 8 FOR UPDATE SKIP LOCKED")){bind(s,scope,epoch);try(var r=s.executeQuery()){while(r.next())selected.add(r.getObject(1,UUID.class));}}
        check(selected.size()<=GC_BATCH,"candidate batch bound");for(UUID id:selected)collectOne(scope,id);return selected.size();});}
    int expireStaging(UUID scope)throws Exception{return tx(()->{
        List<String> expired=new ArrayList<>();try(var s=db.prepareStatement("SELECT name FROM root WHERE scope=? AND expires<=? ORDER BY expires LIMIT 8 FOR UPDATE SKIP LOCKED")){bind(s,scope,epoch);try(var r=s.executeQuery()){while(r.next())expired.add(r.getString(1));}}
        check(expired.size()<=GC_BATCH,"expiry batch bound");for(String name:expired)release(scope,name);return expired.size();});}
    void expiryIndexProof()throws Exception{
        UUID otherScope=uuid(),targetScope=uuid();UUID other=block(otherScope,nativeNode(uuid(),44,64)),target=block(targetScope,nativeNode(uuid(),45,64));
        sql("INSERT INTO root SELECT ?, 'other-'||n,?,0 FROM generate_series(1,20000) n",otherScope,other);
        sql("INSERT INTO root SELECT ?, 'future-'||n,?,100000 FROM generate_series(1,5000) n",targetScope,target);
        sql("INSERT INTO root SELECT ?, 'ready-'||n,?,1 FROM generate_series(1,16) n",targetScope,target);
        sql("ANALYZE root");
        JsonNode plan=JSON.readTree(text("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT name FROM root WHERE scope=? AND expires<=10 ORDER BY expires LIMIT 8 FOR UPDATE SKIP LOCKED",targetScope));
        List<JsonNode> nodes=new ArrayList<>();walkPlan(plan.get(0).path("Plan"),nodes);
        JsonNode scan=nodes.stream().filter(n->n.path("Index Name").asText().equals("root_expiry")).findFirst().orElseThrow(()->new AssertionError("expiry scoped index not used"));
        check(scan.path("Index Cond").asText().contains("scope")&&scan.path("Index Cond").asText().contains("expires"),"expiry index constrains both scope and epoch");
        check(scan.path("Actual Rows").asInt()==8&&scan.path("Rows Removed by Filter").asInt()==0,"eight candidates without filtering foreign/unexpired rows");
        check(plan.get(0).path("Plan").path("Actual Rows").asInt()==8,"expiry batch output eight");
        System.out.println("PLAN,scoped_expiry,"+json(plan));
        System.out.println("PASS,SR5 scoped expiry index:20000 foreign expired roots+5000 local future roots+16 local expired;index(scope,expires) reads8 candidate rows with zero filtered rows");
    }
    void walkPlan(JsonNode node,List<JsonNode> nodes){nodes.add(node);for(JsonNode child:node.path("Plans"))walkPlan(child,nodes);}
    void gc()throws Exception{
        UUID scope=uuid(),key=uuid(),shared=block(scope,nativeNode(key,1,64));UUID leaf=build(scope,"content",List.of(new Entry(key,shared)));UUID fork=save(scope,"content",1,List.of(new Edge(null,leaf,1)));
        pin(scope,"source",fork,null);pin(scope,"fork",fork,null);pin(scope,"draft",leaf,null);pin(scope,"attempt",leaf,null);
        UUID orphan=block(scope,nativeNode(uuid(),2,64));UUID orphanPage=build(scope,"content",List.of(new Entry(uuid(),orphan)));enqueue(scope,orphanPage);
        release(scope,"source");epoch+=3;for(int i=0;i<5;i++){gcBatch(scope);epoch+=3;}
        check(number("SELECT count(*) FROM object WHERE scope=? AND id IN (?,?)",scope,shared,leaf)==2,"shared descendant block survives source deletion");
        check(number("SELECT count(*) FROM object WHERE scope=? AND id IN (?,?)",scope,orphan,orphanPage)==0,"orphan page and block reclaimed");
        UUID stagedTarget=null;
        for(String type:List.of("new-pin","staging-lease")){
            UUID target=block(scope,nativeNode(uuid(),3,64));
            if(type.equals("staging-lease"))stagedTarget=target;
            try(Connection creator=connect();var clients=Executors.newVirtualThreadPerTaskExecutor()){
                creator.setAutoCommit(false);var p=new StorageChoiceExperiment(creator);p.pin(scope,type,target,type.equals("staging-lease")?epoch+20:null);
                var started=new CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicLong();
                Future<Boolean> result=clients.submit(()->{try(Connection c=connect()){var collector=new StorageChoiceExperiment(c);pid.set(collector.number("SELECT pg_backend_pid()"));started.countDown();try{return collector.tx(()->collector.collectOne(scope,target));}catch(SQLException e){check(e.getSQLState().equals("23503"),"FK arbitrates new root race");return false;}}});
                check(started.await(10,TimeUnit.SECONDS),"collector ready");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=number("SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'",pid.get())==1;if(waiting)break;Thread.onSpinWait();}
                check(waiting,"deterministically observed collector FK lock wait");creator.commit();check(!result.get(15,TimeUnit.SECONDS),"new root protects stale candidate");
            }
            check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",scope,target)==1,"pinned block present");
        }
        check(expireStaging(scope)==0,"unexpired staging lease remains durable");
        UUID crash=block(scope,nativeNode(uuid(),4,64));epoch+=3;
        try(Connection c=connect()){c.setAutoCommit(false);new StorageChoiceExperiment(c).collectOne(scope,crash);}
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",scope,crash)==1,"collector disconnect rollback object");
        check(number("SELECT count(*) FROM garbage WHERE scope=? AND object_id=?",scope,crash)==1,"collector disconnect rollback queue");
        for(int i=0;i<10;i++){gcBatch(scope);epoch+=3;}
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",scope,crash)==0,"resumed batch reclaims orphan");
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",scope,shared)==1,"reachable block survives restart");
        epoch+=25;check(expireStaging(scope)==1,"expired staging lease enqueued");epoch+=3;
        for(int i=0;i<5;i++){gcBatch(scope);epoch+=3;}
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",scope,stagedTarget)==0,"expired unreferenced staging block reclaimed after grace");
        System.out.println("PASS,SR5 normalized typed DAG GC max8candidates/32edges per object;source deletion;fork/draft/attempt page-to-block reachability;orphan page+block;deterministic newpin/staging lease races;disconnect+queue restart;bounded expiry+grace");
    }

    Stats stats()throws Exception{
        try(var s=db.createStatement();var r=s.executeQuery("SELECT sum(pg_relation_size(c.oid)),sum(CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_relation_size(c.reltoastrelid) END),sum(pg_indexes_size(c.oid)+CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_indexes_size(c.reltoastrelid) END),pg_wal_lsn_diff(pg_current_wal_insert_lsn(),'0/0')::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='r74_choice' AND c.relkind='r'")){r.next();return new Stats(writes,r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4));}
    }
    void measure(String label,int count,Action work)throws Exception{
        Stats before=stats();long q=queries,b=transferred;double[] ms=new double[count];for(int i=0;i<count;i++){long start=System.nanoTime();work.run();ms[i]=(System.nanoTime()-start)/1e6;}Arrays.sort(ms);Stats after=stats();
        System.out.printf(Locale.ROOT,"METRIC,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f%n",label,count,after.rows-before.rows,queries-q,transferred-b,after.heap-before.heap,after.toast-before.toast,after.index-before.index,after.wal-before.wal,ms[(count-1)/2],ms[(int)Math.ceil(.95*count)-1],ms[(int)Math.ceil(.99*count)-1]);
    }
}
