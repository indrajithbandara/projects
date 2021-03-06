#include <iostream>
#include <string>
#include <cstdarg>
#include "prototypes.h"

using namespace std;

// default constructor
State::State(): _id(0), _parent(0), _cost(1), _visited(false) {}

State::State(int id, string representation, int hcost): _id(id), _h_cost(hcost), _figure(representation), _parent(0), _cost(1), _visited(false) {}

// copy constructor
State::State(State & state){
	_id 			= state.getID();
	_parent 	= state.getParent();
	_cost 		= state.getCost();
	_neighbors	= state.getNeighbors();
	_h_cost	= state.getHCost();
}

string State::getRepresentation(){
	return _figure;
}

int State::getID(){
	return _id;
}

int State::getParent(){
	return _parent;
}

int State::getHCost(){
	return _h_cost;
}

int State::getCost(){
	return _cost;
}

int State::getNeighborsLength(){
	return _nNeighbors;
}

int * State::getNeighbors(){
	return _neighbors;
}

bool State::isVisited(){
	return _visited;
}

void State::setCost(int cost){
	_cost = cost;
}

// takes variable number of arguments, and adds them as the neighbors of the state
void State::setNeighbors(int n, ...){

	_neighbors = new int[n];

	_nNeighbors = n;

	va_list arguments;
	va_start(arguments, n);

	for(int i=0; i < n; ++i){

		_neighbors[i] = va_arg(arguments, int);
		
	}
}

void State::setStatus(bool status){
	_visited = status;
}

void State::setParent(int parent){
	_parent = parent;
}