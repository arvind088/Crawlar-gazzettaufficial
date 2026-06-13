package it.legislation.crawler;

public class Person {
	

		String name;
		int age;
		int [] arry = {1,2,3,4,5};
		public Person(){

		 this.name =  name;
		 this.age = age;
		}

		public void greet(){

		System.out.print("Hello, my name is " + name +"  and I am " 
		 + age + " years old.");
		}
		
		
		
		public void reverseArray(int [] arry) {
	
			for(int i =0; i <(arry.length)/2; i++) {
				  
				   int temp = arry[i];
			        arry[i] = arry[arry.length - 1 - i];
			        arry[arry.length - 1 - i] = temp;
				
			}			 
			
			
		}
		
		public void Max(int [] arry) {
			
			int temp =0;
			for(int i =0; i <arry.length; i++) {
				if(arry[i]> temp)
				{
					temp = arry[i];
				}

			}			 
			System.out.print(temp);
			
		}


		
		public static void main(String [] argument ) {
			
			Person p = new Person();
			p.name= "arvind";
			p.age = 27;
			
			p.greet();
			//p.reverseArray(p.arry);
			
			p.Max(p.arry);
			
			//for (int i = 0; i < p.arry.length; i++) 
	        //    System.out.print(p.arry[i] + " ");
		
			//String s = null;
			//System.out.println(s.length());
		}
		
		
}
