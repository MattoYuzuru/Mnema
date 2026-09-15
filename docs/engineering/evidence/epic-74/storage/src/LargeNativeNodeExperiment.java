package app.mnema.learning.storage_spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Bounded physical-fragment proof; semantic node IDs and native format never change. */
public final class LargeNativeNodeExperiment {
    static final ObjectMapper JSON=new ObjectMapper();
    static final UUID SCOPE=UUID.fromString("74000000-0000-4000-8000-000000000077");
    static final int FRAGMENT_BYTES=4096,PHYSICAL_LIMIT=16384,FANOUT=32;
    final Connection db;long writes,reads,bytes;
    record Snapshot(UUID root,UUID header,List<UUID> fragments,List<String> parts) { }
    record Size(long heap,long toastBytes,long indexes,long wal) { }
    @FunctionalInterface interface Action {void run()throws Exception;}
    @FunctionalInterface interface Transaction<T>{T run()throws Exception;}
    LargeNativeNodeExperiment(Connection db){this.db=db;}
    public static void main(String[] args)throws Exception{
        int port=Integer.parseInt(System.getenv().getOrDefault("R74_LARGE_PORT","15477"));
        if(port<1024||port>65535)throw new IllegalArgumentException("invalid loopback port");
        try(Connection db=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/mnema_r74_large","mnema","synthetic-only")){
            var x=new LargeNativeNodeExperiment(db);x.sql("CREATE SCHEMA r74_large; SET search_path TO r74_large; SET statement_timeout='30s'; SET lock_timeout='5s'");x.schema();
            System.out.println("ENV,"+x.text("SELECT version()")+",java="+System.getProperty("java.version")+",heap="+Runtime.getRuntime().maxMemory());
            System.out.println("METRIC,label,operations,row_mutations,payload_queries,utf8_payload_bytes,heap_delta,toast_delta,index_delta,wal_bytes,p50_ms,p95_ms");
            x.run();System.out.println("PASS,LARGE_NATIVE_NODE_ALL_ASSERTIONS");
        }
    }
    void schema()throws Exception{sql("""
        CREATE TABLE object(scope uuid NOT NULL,id uuid NOT NULL,kind text NOT NULL CHECK(kind IN ('header','fragment','manifest')),
          payload jsonb NOT NULL,PRIMARY KEY(scope,id),CHECK(octet_length(payload::text)<=16384));
        CREATE TABLE edge(scope uuid NOT NULL,parent uuid NOT NULL,ordinal int NOT NULL CHECK(ordinal BETWEEN 0 AND 31),child uuid NOT NULL,
          PRIMARY KEY(scope,parent,ordinal),FOREIGN KEY(scope,parent) REFERENCES object(scope,id) ON DELETE CASCADE,
          FOREIGN KEY(scope,child) REFERENCES object(scope,id));
        CREATE INDEX incoming_edge ON edge(scope,child);
        CREATE TABLE pin(scope uuid NOT NULL,name text NOT NULL,root uuid NOT NULL,PRIMARY KEY(scope,name),FOREIGN KEY(scope,root) REFERENCES object(scope,id));
        CREATE INDEX incoming_pin ON pin(scope,root);
        CREATE TABLE candidate(scope uuid NOT NULL,id uuid NOT NULL,PRIMARY KEY(scope,id));
        """);}
    void run()throws Exception{
        UUID documentId=UUID.randomUUID(),paragraphId=UUID.randomUUID(),textId=UUID.randomUUID();
        ObjectNode nativeDoc=fixture(documentId,paragraphId,textId);String original=find(nativeDoc,textId).path("text").asText();
        check(utf8(original)>PHYSICAL_LIMIT&&utf8(original)<=32768,"one native scalar between16KiB and32KiB");
        check(utf8(JSON.writeValueAsString(nativeDoc))<1_048_576,"within proposed material envelope");
        System.out.println("FIXTURE,native_utf8="+utf8(JSON.writeValueAsString(nativeDoc))+",long_text_utf8="+utf8(original)+",physical_fragment_target="+FRAGMENT_BYTES+",physical_object_limit="+PHYSICAL_LIMIT);
        Snapshot[] initial={null};
        measure("large_node_initial_save",1,()->initial[0]=tx(()->store(nativeDoc,textId)));
        pin("revision0",initial[0].root);
        check(initial[0].fragments.size()>1,"one semantic node uses several physical fragments");
        check(read(initial[0].root).equals(nativeDoc),"initial exact native JSON reassembly");

        int replaceOffset=original.offsetByCodePoints(0,100),replaceEnd=original.offsetByCodePoints(replaceOffset,1);
        String replacement="Ж";ObjectNode replaced=nativeDoc.deepCopy();find(replaced,textId).put("text",original.substring(0,replaceOffset)+replacement+original.substring(replaceEnd));
        Snapshot[] edited={null};
        measure("large_node_tiny_replace",1,()->edited[0]=tx(()->edit(initial[0],replaceOffset,replaceEnd-replaceOffset,replacement)));
        pin("revision1",edited[0].root);
        check(read(edited[0].root).equals(replaced),"tiny replacement exact native JSON");
        reuse(initial[0],edited[0],"replacement");

        String textAfter=find(replaced,textId).path("text").asText();int insertOffset=textAfter.offsetByCodePoints(0,200);String insertion=" Ω🌿 ";
        ObjectNode inserted=replaced.deepCopy();find(inserted,textId).put("text",textAfter.substring(0,insertOffset)+insertion+textAfter.substring(insertOffset));
        Snapshot[] withInsert={null};
        measure("large_node_short_insert",1,()->withInsert[0]=tx(()->edit(edited[0],insertOffset,0,insertion)));
        pin("revision2",withInsert[0].root);reuse(edited[0],withInsert[0],"insertion");
        List<Snapshot> history=List.of(initial[0],edited[0],withInsert[0]);List<JsonNode> expected=List.of(nativeDoc,replaced,inserted);
        for(int i=0;i<history.size();i++){int version=i;measure("large_node_history"+i,10,()->check(read(history.get(version).root).equals(expected.get(version)),"complete exact historic native JSON"));}
        Set<String> originalSemanticIds=semanticIds(nativeDoc);
        check(semanticIds(read(withInsert[0].root)).equals(originalSemanticIds),"physical editing leaves all semantic IDs unchanged");
        for(Snapshot snap:history){check(!originalSemanticIds.contains(snap.root.toString()),"physical manifest ID is separate");for(UUID fragment:snap.fragments)check(!originalSemanticIds.contains(fragment.toString()),"physical fragment ID is separate");}
        check(number("SELECT max(octet_length(payload::text)) FROM object")<=PHYSICAL_LIMIT,"all physical objects byte bounded");
        System.out.println("BOUNDS,max_object_jsonb_bytes="+number("SELECT max(octet_length(payload::text)) FROM object")+",fragment_count="+initial[0].fragments.size()+",semantic_ids="+originalSemanticIds.size());

        UUID sharedFragment=initial[0].fragments.getLast();
        try{sql("DELETE FROM object WHERE scope=? AND id=?",SCOPE,sharedFragment);throw new AssertionError("referenced fragment deleted");}
        catch(SQLException expectedFailure){check(expectedFailure.getSQLState().equals("23503"),"page-to-fragment FK protects fragment");}
        drain();for(int i=0;i<history.size();i++)check(read(history.get(i).root).equals(expected.get(i)),"retained history survives GC");
        release("revision0");release("revision1");drain();
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",SCOPE,initial[0].fragments.getFirst())==0,"superseded unpinned fragment reclaimed");
        check(number("SELECT count(*) FROM object WHERE scope=? AND id=?",SCOPE,sharedFragment)==1,"shared fragment retained by current page");
        check(read(withInsert[0].root).equals(inserted),"current exact native JSON survives history collection");
        System.out.println("PASS,single oversized semantic text;Russian/RTL/ruby/emoji context;stable paragraph/text IDs;physical fragment replacement and insertion reuse;exact old history;page-fragment FK and bounded GC seam");
        System.out.println("ROWS,object,"+number("SELECT count(*) FROM object"));System.out.println("ROWS,edge,"+number("SELECT count(*) FROM edge"));
    }
    ObjectNode fixture(UUID docId,UUID paragraphId,UUID textId){
        ObjectNode doc=JSON.createObjectNode();doc.put("formatVersion",1);ObjectNode root=doc.putObject("root");root.put("id",docId.toString());root.put("type","doc");root.put("version",1);root.putObject("attrs");
        ObjectNode p=root.putArray("content").addObject();p.put("id",paragraphId.toString());p.put("type","paragraph");p.put("version",1);p.putObject("attrs").put("lang","ru").put("dir","auto");
        var children=p.putArray("content");ObjectNode text=children.addObject();text.put("id",textId.toString());text.put("type","text");text.put("version",1);text.putObject("attrs");
        int[] alphabet="абвгдежзийклмнопрстуфхцчшщыэюя العربية漢字 🌿".codePoints().toArray();Random random=new Random(7477);StringBuilder longText=new StringBuilder("Русский текст и العربية: ");int length=utf8(longText.toString());
        while(length<24560){int cp=alphabet[random.nextInt(alphabet.length)];String next=new String(Character.toChars(cp));longText.append(next);length+=utf8(next);}text.put("text",longText.toString());
        ObjectNode ruby=children.addObject();ruby.put("id",UUID.randomUUID().toString());ruby.put("type","ruby");ruby.put("version",1);ruby.putObject("attrs").put("base","漢字").put("reading","かんじ");ruby.putArray("content");
        ObjectNode rtl=children.addObject();rtl.put("id",UUID.randomUUID().toString());rtl.put("type","text");rtl.put("version",1);rtl.putObject("attrs").put("dir","rtl").put("lang","ar");rtl.put("text","النص العربي");return doc;
    }
    Snapshot store(JsonNode document,UUID semanticTextId)throws Exception{
        ObjectNode skeleton=document.deepCopy();String text=find(skeleton,semanticTextId).path("text").asText();find(skeleton,semanticTextId).put("text","");
        ObjectNode header=JSON.createObjectNode();header.put("storageFormat",1);header.put("textSlotNodeId",semanticTextId.toString());header.set("nativeSkeleton",skeleton);
        UUID headerId=object("header",header);List<String> parts=split(text);List<UUID> ids=new ArrayList<>();for(String part:parts)ids.add(fragment(part));return manifest(headerId,ids,parts);
    }
    Snapshot edit(Snapshot before,int utf16Offset,int deleteCharacters,String insert)throws Exception{
        List<String> parts=new ArrayList<>(before.parts);List<UUID> ids=new ArrayList<>(before.fragments);int offset=utf16Offset,index=0;
        while(index<parts.size()-1&&offset>=parts.get(index).length())offset-=parts.get(index++).length();
        String selected=parts.get(index);check(offset+deleteCharacters<=selected.length(),"bounded local edit fixture");
        check(!insideSurrogate(selected,offset)&&!insideSurrogate(selected,offset+deleteCharacters),"edit boundaries preserve Unicode scalar values");
        String changed=selected.substring(0,offset)+insert+selected.substring(offset+deleteCharacters);
        // An insertion may increase this fragment slightly. The physical cap remains16KiB;
        // re-split only the touched fragment if it crosses the8KiB local high-water mark.
        List<String> replacement=utf8(changed)>8192?split(changed):List.of(changed);
        parts.remove(index);ids.remove(index);for(int i=0;i<replacement.size();i++){parts.add(index+i,replacement.get(i));ids.add(index+i,fragment(replacement.get(i)));}
        return manifest(before.header,ids,parts);
    }
    static boolean insideSurrogate(String text,int offset){return offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset));}
    static List<String> split(String value){List<String> parts=new ArrayList<>();int start=0,bytes=0;for(int offset=0;offset<value.length();){int cp=value.codePointAt(offset),width=Character.charCount(cp);int n=utf8(value.substring(offset,offset+width));if(bytes+n>FRAGMENT_BYTES){parts.add(value.substring(start,offset));start=offset;bytes=0;}bytes+=n;offset+=width;}parts.add(value.substring(start));return parts;}
    UUID fragment(String value)throws Exception{ObjectNode payload=JSON.createObjectNode();payload.put("text",value);return object("fragment",payload);}
    Snapshot manifest(UUID header,List<UUID> fragments,List<String> parts)throws Exception{
        check(fragments.size()+1<=FANOUT,"bounded single-page manifest for accepted32KiB scalar fixture");
        ObjectNode metadata=JSON.createObjectNode();metadata.put("storageFormat",1);metadata.put("fragmentCount",fragments.size());metadata.put("utf16Length",parts.stream().mapToInt(String::length).sum());
        UUID root=object("manifest",metadata);List<UUID> children=new ArrayList<>(List.of(header));children.addAll(fragments);
        try(var s=db.prepareStatement("INSERT INTO edge VALUES (?,?,?,?)")){for(int i=0;i<children.size();i++){bind(s,SCOPE,root,i,children.get(i));s.addBatch();writes++;}s.executeBatch();}
        return new Snapshot(root,header,List.copyOf(fragments),List.copyOf(parts));
    }
    UUID object(String kind,JsonNode payload)throws Exception{
        String encoded=JSON.writeValueAsString(payload);check(number("SELECT octet_length(?::jsonb::text)",encoded)<=PHYSICAL_LIMIT,"physical object byte limit");
        UUID id=UUID.randomUUID();sql("INSERT INTO object VALUES (?,?,?,?::jsonb)",SCOPE,id,kind,encoded);writes++;enqueue(id);return id;
    }
    JsonNode read(UUID root)throws Exception{
        reads++;StringBuilder text=new StringBuilder();ObjectNode header=null;int count=0;
        try(var s=db.prepareStatement("SELECT e.ordinal,o.kind,o.payload::text FROM edge e JOIN object o ON o.scope=e.scope AND o.id=e.child WHERE e.scope=? AND e.parent=? ORDER BY e.ordinal")){
            bind(s,SCOPE,root);try(var r=s.executeQuery()){while(r.next()){String encoded=r.getString(3);bytes+=utf8(encoded);JsonNode payload=JSON.readTree(encoded);if(r.getInt(1)==0){check(r.getString(2).equals("header"),"typed header edge");header=(ObjectNode)payload;}else{check(r.getString(2).equals("fragment"),"typed fragment edge");text.append(payload.path("text").asText());}count++;}}}
        check(header!=null&&count<=FANOUT,"bounded complete manifest");ObjectNode result=header.path("nativeSkeleton").deepCopy();find(result,UUID.fromString(header.path("textSlotNodeId").asText())).put("text",text.toString());return result;
    }
    static ObjectNode find(JsonNode node,UUID id){if(node.isObject()&&node.path("id").asText().equals(id.toString()))return(ObjectNode)node;if(node.isContainerNode())for(JsonNode child:node){ObjectNode result=find(child,id);if(result!=null)return result;}return null;}
    static Set<String> semanticIds(JsonNode node){Set<String> ids=new HashSet<>();if(node.isObject()&&node.has("id"))ids.add(node.path("id").asText());if(node.isContainerNode())for(JsonNode child:node)ids.addAll(semanticIds(child));return ids;}
    void reuse(Snapshot old,Snapshot next,String operation){check(old.header.equals(next.header),"unchanged header reused");check(old.fragments.size()==next.fragments.size(),"no fixture-wide rechunk");int reused=0;for(int i=0;i<old.fragments.size();i++)if(old.fragments.get(i).equals(next.fragments.get(i)))reused++;check(reused==old.fragments.size()-1,"all untouched fragments reused");System.out.println("REUSE,"+operation+",unchanged_fragments="+reused+",changed_fragments=1,header_reused=true");}
    void pin(String name,UUID root)throws Exception{sql("INSERT INTO pin VALUES (?,?,?)",SCOPE,name,root);writes++;}
    void enqueue(UUID id)throws Exception{sql("INSERT INTO candidate VALUES (?,?) ON CONFLICT DO NOTHING",SCOPE,id);writes++;}
    void release(String name)throws Exception{tx(()->{UUID root=UUID.fromString(text("SELECT root FROM pin WHERE scope=? AND name=?",SCOPE,name));sql("DELETE FROM pin WHERE scope=? AND name=?",SCOPE,name);enqueue(root);return null;});}
    int collect()throws Exception{return tx(()->{List<UUID> selected=new ArrayList<>();try(var s=db.prepareStatement("SELECT id FROM candidate WHERE scope=? ORDER BY id LIMIT 8 FOR UPDATE SKIP LOCKED")){bind(s,SCOPE);try(var r=s.executeQuery()){while(r.next())selected.add(r.getObject(1,UUID.class));}}
        for(UUID id:selected){List<UUID> children=new ArrayList<>();try(var s=db.prepareStatement("SELECT child FROM edge WHERE scope=? AND parent=?")){bind(s,SCOPE,id);try(var r=s.executeQuery()){while(r.next())children.add(r.getObject(1,UUID.class));}}check(children.size()<=FANOUT,"bounded outgoing references");
            long removed=number("WITH gone AS (DELETE FROM object o WHERE scope=? AND id=? AND NOT EXISTS(SELECT 1 FROM edge e WHERE e.scope=o.scope AND e.child=o.id) AND NOT EXISTS(SELECT 1 FROM pin p WHERE p.scope=o.scope AND p.root=o.id) RETURNING id) SELECT count(*) FROM gone",SCOPE,id);
            sql("DELETE FROM candidate WHERE scope=? AND id=?",SCOPE,id);if(removed==1)for(UUID child:children)enqueue(child);}
        return selected.size();});}
    void drain()throws Exception{for(int i=0;i<20;i++)if(collect()==0)return;throw new AssertionError("bounded fixture queue did not drain");}
    <T>T tx(Transaction<T> action)throws Exception{db.setAutoCommit(false);try{T v=action.run();db.commit();return v;}catch(Exception|AssertionError e){db.rollback();throw e;}finally{db.setAutoCommit(true);}}
    void sql(String q,Object...args)throws SQLException{try(var s=db.prepareStatement(q)){bind(s,args);s.execute();}}
    long number(String q,Object...args)throws SQLException{try(var s=db.prepareStatement(q)){bind(s,args);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    String text(String q,Object...args)throws SQLException{try(var s=db.prepareStatement(q)){bind(s,args);try(var r=s.executeQuery()){r.next();return r.getString(1);}}}
    static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    static int utf8(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    Size size()throws Exception{try(var s=db.createStatement();var r=s.executeQuery("SELECT sum(pg_relation_size(c.oid)),sum(CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_relation_size(c.reltoastrelid) END),sum(pg_indexes_size(c.oid)+CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_indexes_size(c.reltoastrelid) END),pg_wal_lsn_diff(pg_current_wal_insert_lsn(),'0/0')::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='r74_large' AND c.relkind='r'")){r.next();return new Size(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4));}}
    void measure(String name,int count,Action action)throws Exception{Size before=size();long w=writes,r=reads,b=bytes;double[] times=new double[count];for(int i=0;i<count;i++){long start=System.nanoTime();action.run();times[i]=(System.nanoTime()-start)/1e6;}Arrays.sort(times);Size after=size();System.out.printf(Locale.ROOT,"METRIC,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f%n",name,count,writes-w,reads-r,bytes-b,after.heap-before.heap,after.toastBytes-before.toastBytes,after.indexes-before.indexes,after.wal-before.wal,times[(count-1)/2],times[(int)Math.ceil(.95*count)-1]);}
}
