package nl.adgroot.pdfsummarizer.notes;

import static nl.adgroot.pdfsummarizer.notes.NotesWriter.safeFileName;

public class CardsPage {
 public String content = "";
 public String topic = "";
 public String chapter = "";

 public CardsPage(String card){
   addCard(card);
 }

 public CardsPage(){}

  public void addCard(String card){
   if(!content.isEmpty()){
     content += "\n\n--------------------------------------------------\n\n";
   }
   content += card;
  }

  public void addTopic(String topic){
   this.topic = safeFileName(topic);
  }

  public void addChapter(String chapter){
   this.chapter = safeFileName(chapter);
  }

  private String getHashtag(){
   String hashtag =
       "#flashcards/"+topic+"\n"+
       "#flashcards/"+topic+"/"+chapter;

   return hashtag;
  }

  @Override
  public String toString() {
   return content + "\n\n" + getHashtag();
  }
}
